package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringManifestServiceTest {

    private static final String COMPONENT_ID = "praxis-table";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AiRegistryRepository repository;

    @Test
    void validatesExecutableManifestShapeAndPlan() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "columns": [
                      { "field": "email", "header": "Email" }
                    ]
                  },
                  "plan": {
                    "operations": [
                      {
                        "operationId": "column.header.set",
                        "target": "email",
                        "input": { "header": "Contato" }
                      }
                    ]
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult result = service.validateEditPlan(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.valid()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.normalizedPlan().path("operations")).hasSize(1);
    }

    @Test
    void resolvesTargetUsingOperationTargetResolverWithoutEffectInference() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "columns": [
                      { "field": "email", "header": "Email" }
                    ]
                  },
                  "operationId": "column.header.set",
                  "target": "email"
                }
                """);

        AgenticAuthoringResolvedTarget result = service.resolveTarget(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringResolveTargetRequest.class));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.kind()).isEqualTo("column");
        assertThat(result.resolver()).isEqualTo("column-by-field");
        assertThat(result.path()).isEqualTo("columns[]/0");
    }

    @Test
    void resolvesFieldByNameOrLabelEvenWhenEffectHasNoKey() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "fieldMetadata": [
                      { "name": "email", "label": "E-mail corporativo", "source": "local" }
                    ]
                  },
                  "operationId": "field.label.set",
                  "target": "E-mail corporativo"
                }
                """);

        AgenticAuthoringResolvedTarget result = service.resolveTarget(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringResolveTargetRequest.class));

        assertThat(result.status()).isEqualTo("resolved");
        assertThat(result.resolver()).isEqualTo("field-by-name-or-label");
        assertThat(result.path()).isEqualTo("fieldMetadata[]/0");
    }

    @Test
    void rejectsInvalidEnumFromInputSchema() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "columns": [
                      { "field": "email", "header": "Email" }
                    ]
                  },
                  "plan": {
                    "operationId": "column.align.set",
                    "target": "email",
                    "input": { "align": "middle" }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult result = service.validateEditPlan(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.valid()).isFalse();
        assertThat(result.failures()).anyMatch(failure -> failure.contains("must be one of"));
    }

    @Test
    void executesRemoteResourceBindingValidator() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {},
                  "plan": {
                    "operationId": "expansion.detailSource.configure",
                    "input": {
                      "resourcePath": { "path": "https://evil.example/api/customers" }
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult result = service.validateEditPlan(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.valid()).isFalse();
        assertThat(result.failures()).anyMatch(failure -> failure.contains("remote-resource-binding-safe failed"));
    }

    @Test
    void rejectsDestructivePlanWithoutConfirmation() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "columns": [
                      { "field": "email", "header": "Email" }
                    ]
                  },
                  "plan": {
                    "operationId": "column.remove",
                    "target": "email",
                    "input": {}
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult result = service.validateEditPlan(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.valid()).isFalse();
        assertThat(result.failures()).contains("operation requires explicit confirmation: column.remove");
    }

    @Test
    void compilesMergeByKeyEffectWithResolvedKeyAndValue() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
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
                """);

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.patch().path("operations")).hasSize(1);
        JsonNode op = result.patch().path("operations").get(0);
        assertThat(op.path("effectKind").asText()).isEqualTo("merge-by-key");
        assertThat(op.path("resolvedPath").asText()).isEqualTo("columns[]/0");
        assertThat(op.path("keyValue").asText()).isEqualTo("email");
        assertThat(op.path("value").path("header").asText()).isEqualTo("Contato");
        assertThat(result.patch().path("proposedConfig").path("columns").get(0).path("header").asText())
                .isEqualTo("Contato");
    }

    @Test
    void compilesSetValueEffectIntoProposedConfig() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "fieldMetadata": [
                      { "name": "email", "label": "Email", "source": "local" }
                    ]
                  },
                  "plan": {
                    "operationId": "field.label.set",
                    "target": "email",
                    "input": { "label": "E-mail Corporativo" }
                  }
                }
                """);

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.patch().path("operations").get(0).path("op").asText()).isEqualTo("set-value");
        assertThat(result.patch().path("proposedConfig").path("fieldMetadata").get(0).path("label").asText())
                .isEqualTo("E-mail Corporativo");
    }

    @Test
    void compilesRemoveByKeyEffectIntoProposedConfig() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(validPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "columns": [
                      { "field": "email", "header": "Email" },
                      { "field": "name", "header": "Name" }
                    ]
                  },
                  "plan": {
                    "operationId": "column.remove",
                    "target": "email",
                    "input": {},
                    "confirmed": true
                  }
                }
                """);

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.patch().path("operations").get(0).path("op").asText()).isEqualTo("remove-by-key");
        assertThat(result.patch().path("operations").get(0).path("removedIndex").asInt()).isZero();
        assertThat(result.patch().path("proposedConfig").path("columns")).hasSize(1);
        assertThat(result.patch().path("proposedConfig").path("columns").get(0).path("field").asText())
                .isEqualTo("name");
    }

    @Test
    void compilesDynamicFormLabelChangeFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-dynamic-form",
                payloadFromClasspathSnapshot("praxis-dynamic-form"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "fieldMetadata": [
                      { "name": "email", "label": "Email", "source": "schema" }
                    ]
                  },
                  "plan": {
                    "operationId": "field.label.set",
                    "target": "Email",
                    "input": { "label": "E-mail Corporativo" }
                  }
                }
                """);

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-dynamic-form",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.5.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("field.label.set");
        assertThat(operation.path("op").asText()).isEqualTo("merge-by-key");
        assertThat(operation.path("resolvedPath").asText()).isEqualTo("fieldMetadata[]/0");
        assertThat(operation.path("keyValue").asText()).isEqualTo("email");
        assertThat(operation.path("value").path("label").asText()).isEqualTo("E-mail Corporativo");
        assertThat(result.patch().path("proposedConfig").path("fieldMetadata").get(0).path("label").asText())
                .isEqualTo("E-mail Corporativo");
    }

    @Test
    void enforcesDynamicFormRuleRemovalConfirmationFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-dynamic-form",
                payloadFromClasspathSnapshot("praxis-dynamic-form"));
        JsonNode unconfirmedRequest = objectMapper.readTree("""
                {
                  "config": {
                    "formRules": [
                      { "id": "ocultar-cpf-pj", "name": "Ocultar CPF para PJ" }
                    ]
                  },
                  "plan": {
                    "operationId": "rule.remove",
                    "target": "ocultar-cpf-pj",
                    "input": {}
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult unconfirmed = service.validateEditPlan(
                "praxis-dynamic-form",
                objectMapper.treeToValue(unconfirmedRequest, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(unconfirmed.valid()).isFalse();
        assertThat(unconfirmed.failures()).contains("operation requires explicit confirmation: rule.remove");

        JsonNode confirmedRequest = objectMapper.readTree("""
                {
                  "config": {
                    "formRules": [
                      { "id": "ocultar-cpf-pj", "name": "Ocultar CPF para PJ" }
                    ]
                  },
                  "plan": {
                    "operationId": "rule.remove",
                    "target": "ocultar-cpf-pj",
                    "input": {},
                    "confirmed": true
                  }
                }
                """);

        AgenticAuthoringManifestCompileResult confirmed = service.compilePatch(
                "praxis-dynamic-form",
                objectMapper.treeToValue(confirmedRequest, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(confirmed.compiled()).isTrue();
        assertThat(confirmed.patch().path("operations").get(0).path("op").asText()).isEqualTo("remove-by-key");
        assertThat(confirmed.patch().path("operations").get(0).path("removedIndex").asInt()).isZero();
        assertThat(confirmed.patch().path("proposedConfig").path("formRules")).isEmpty();
    }

    @Test
    void compilesTableColumnHeaderChangeFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-table",
                payloadFromClasspathSnapshot("praxis-table"));
        JsonNode request = objectMapper.readTree("""
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
                """);

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-table",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("2.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("column.header.set");
        assertThat(operation.path("op").asText()).isEqualTo("merge-by-key");
        assertThat(operation.path("resolvedPath").asText()).isEqualTo("columns[]/0");
        assertThat(operation.path("keyValue").asText()).isEqualTo("email");
        assertThat(operation.path("value").path("header").asText()).isEqualTo("Contato");
        assertThat(result.patch().path("proposedConfig").path("columns").get(0).path("header").asText())
                .isEqualTo("Contato");
    }

    @Test
    void validatesTableRemoteBindingOperationFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-table",
                payloadFromClasspathSnapshot("praxis-table"));
        JsonNode unsafeRequest = objectMapper.readTree("""
                {
                  "config": {
                    "behavior": {
                      "expansion": {
                        "detail": {
                          "source": {}
                        }
                      }
                    }
                  },
                  "plan": {
                    "operationId": "expansion.detailSource.configure",
                    "input": {
                      "mode": "resourcePath",
                      "resourcePath": { "path": "https://evil.example/api/customers" }
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult unsafe = service.validateEditPlan(
                "praxis-table",
                objectMapper.treeToValue(unsafeRequest, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(unsafe.valid()).isFalse();
        assertThat(unsafe.failures()).anyMatch(failure -> failure.contains("remote-resource-binding-safe failed"));

        JsonNode safeRequest = objectMapper.readTree("""
                {
                  "config": {
                    "behavior": {
                      "expansion": {
                        "detail": {
                          "source": {}
                        }
                      }
                    }
                  },
                  "plan": {
                    "operationId": "expansion.detailSource.configure",
                    "input": {
                      "mode": "resourcePath",
                      "resourcePath": {
                        "path": "/api/customers/{id}/detail",
                        "method": "GET"
                      }
                    }
                  }
                }
                """);

        AgenticAuthoringManifestCompileResult safe = service.compilePatch(
                "praxis-table",
                objectMapper.treeToValue(safeRequest, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(safe.compiled()).isTrue();
        assertThat(safe.failures()).isEmpty();
        JsonNode operation = safe.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("expansion.detailSource.configure");
        assertThat(operation.path("op").asText()).isEqualTo("merge-object");
        assertThat(operation.path("path").asText()).isEqualTo("behavior.expansion.detail.source");
        assertThat(operation.path("submissionImpact").asText()).isEqualTo("affects-remote-binding");
        assertThat(safe.patch()
                        .path("proposedConfig")
                        .path("behavior")
                        .path("expansion")
                        .path("detail")
                        .path("source")
                        .path("resourcePath")
                        .path("path")
                        .asText())
                .isEqualTo("/api/customers/{id}/detail");
    }

    @Test
    void compilesSettingsPanelShellConfigurationFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-settings-panel",
                payloadFromClasspathSnapshot("praxis-settings-panel"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "config": {
                      "id": "settings-panel",
                      "title": "Preferencias",
                      "titleIcon": "settings"
                    }
                  },
                  "plan": {
                    "operationId": "panel.shell.configure",
                    "input": {
                      "title": "Preferencias avancadas",
                      "titleIcon": "tune"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-settings-panel",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-settings-panel",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("panel.shell.configure");
        assertThat(operation.path("op").asText()).isEqualTo("merge-object");
        assertThat(operation.path("path").asText()).isEqualTo("config");
        assertThat(operation.path("resolvedPath").asText()).isEqualTo("$");
        assertThat(result.patch().path("proposedConfig").path("config").path("title").asText())
                .isEqualTo("Preferencias avancadas");
        assertThat(result.patch().path("proposedConfig").path("config").path("titleIcon").asText())
                .isEqualTo("tune");
    }

    @Test
    void compilesTabsLabelChangeFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-tabs",
                payloadFromClasspathSnapshot("praxis-tabs"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "tabs": [
                      { "id": "general", "textLabel": "Geral", "icon": "settings" },
                      { "id": "security", "textLabel": "Seguranca", "icon": "shield" }
                    ]
                  },
                  "plan": {
                    "operationId": "tab.label.set",
                    "target": "Geral",
                    "input": {
                      "textLabel": "Visao geral"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: tab-exists for tab.label.set");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("tab.label.set");
        assertThat(operation.path("op").asText()).isEqualTo("merge-by-key");
        assertThat(operation.path("resolvedPath").asText()).isEqualTo("tabs[]/0");
        assertThat(operation.path("keyValue").asText()).isEqualTo("general");
        assertThat(operation.path("value").path("textLabel").asText()).isEqualTo("Visao geral");
        assertThat(result.patch().path("proposedConfig").path("tabs").get(0).path("textLabel").asText())
                .isEqualTo("Visao geral");
    }

    @Test
    void compilesTabsDomainPatchWhenHandlerExists() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-tabs",
                payloadFromClasspathSnapshot("praxis-tabs"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "tabs": [
                      { "id": "general", "textLabel": "Geral" },
                      { "id": "security", "textLabel": "Seguranca" }
                    ],
                    "group": {
                      "selectedIndex": 1
                    }
                  },
                  "plan": {
                    "operationId": "tab.order.set",
                    "target": "security",
                    "input": {
                      "beforeTabId": "general"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: tab-exists for tab.order.set")
                .contains("validator declared without backend implementation: tab-order-deterministic for tab.order.set");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("op").asText()).isEqualTo("reorder-by-key");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("tabs.reorder-tab-and-preserve-selection");
        assertThat(operation.path("keyValue").asText()).isEqualTo("security");
        assertThat(operation.path("fromIndex").asInt()).isEqualTo(1);
        assertThat(operation.path("toIndex").asInt()).isZero();
        assertThat(operation.path("selectedIndexBefore").asInt()).isEqualTo(1);
        assertThat(operation.path("selectedIndexAfter").asInt()).isZero();
        assertThat(result.patch().path("proposedConfig").path("tabs").get(0).path("id").asText())
                .isEqualTo("security");
        assertThat(result.patch().path("proposedConfig").path("tabs").get(1).path("id").asText())
                .isEqualTo("general");
        assertThat(result.patch().path("proposedConfig").path("group").path("selectedIndex").asInt())
                .isZero();
    }

    @Test
    void compilesTabsRemoveDomainPatchWhenHandlerExists() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-tabs",
                payloadFromClasspathSnapshot("praxis-tabs"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "tabs": [
                      { "id": "general", "textLabel": "Geral" },
                      { "id": "security", "textLabel": "Seguranca" },
                      { "id": "audit", "textLabel": "Auditoria" }
                    ],
                    "group": {
                      "selectedIndex": 1
                    }
                  },
                  "plan": {
                    "operationId": "tab.remove",
                    "target": "security",
                    "input": {},
                    "confirmed": true
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: tab-exists for tab.remove")
                .contains("validator declared without backend implementation: active-tab-removal-safe for tab.remove")
                .contains("validator declared without backend implementation: tab-content-removal-confirmed for tab.remove");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("op").asText()).isEqualTo("remove-by-key");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("tabs.remove-tab-and-reselect");
        assertThat(operation.path("keyValue").asText()).isEqualTo("security");
        assertThat(operation.path("removedIndex").asInt()).isEqualTo(1);
        assertThat(operation.path("selectedIndexBefore").asInt()).isEqualTo(1);
        assertThat(operation.path("selectedIndexAfter").asInt()).isEqualTo(1);
        assertThat(result.patch().path("proposedConfig").path("tabs")).hasSize(2);
        assertThat(result.patch().path("proposedConfig").path("tabs").get(0).path("id").asText())
                .isEqualTo("general");
        assertThat(result.patch().path("proposedConfig").path("tabs").get(1).path("id").asText())
                .isEqualTo("audit");
        assertThat(result.patch().path("proposedConfig").path("group").path("selectedIndex").asInt())
                .isEqualTo(1);
    }

    @Test
    void compilesTabsSetActiveDomainPatchWhenHandlerExists() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-tabs",
                payloadFromClasspathSnapshot("praxis-tabs"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "tabs": [
                      { "id": "general", "textLabel": "Geral" },
                      { "id": "security", "textLabel": "Seguranca" }
                    ],
                    "group": {
                      "selectedIndex": 0
                    },
                    "nav": {
                      "selectedIndex": 0
                    }
                  },
                  "plan": {
                    "operationId": "tab.active.set",
                    "target": "security",
                    "input": {
                      "selectedIndex": 1,
                      "tabId": "security"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: active-tab-exists for tab.active.set")
                .contains("validator declared without backend implementation: selected-index-in-range for tab.active.set");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-tabs",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("op").asText()).isEqualTo("set-active-index");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("tabs.set-active-item");
        assertThat(operation.path("selectedIndex").asInt()).isEqualTo(1);
        assertThat(operation.path("selectedTabId").asText()).isEqualTo("security");
        assertThat(operation.path("groupSelectedIndexBefore").asInt()).isZero();
        assertThat(operation.path("groupSelectedIndexAfter").asInt()).isEqualTo(1);
        assertThat(operation.path("navSelectedIndexBefore").asInt()).isZero();
        assertThat(operation.path("navSelectedIndexAfter").asInt()).isEqualTo(1);
        assertThat(result.patch().path("proposedConfig").path("group").path("selectedIndex").asInt())
                .isEqualTo(1);
        assertThat(result.patch().path("proposedConfig").path("nav").path("selectedIndex").asInt())
                .isEqualTo(1);
    }

    @Test
    void compilesStepperLabelChangeFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-stepper",
                payloadFromClasspathSnapshot("praxis-stepper"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "steps": [
                      { "id": "account", "label": "Conta" },
                      { "id": "review", "label": "Revisao" }
                    ]
                  },
                  "plan": {
                    "operationId": "step.label.set",
                    "target": "Conta",
                    "input": {
                      "label": "Dados da conta"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-stepper",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: step-exists for step.label.set");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-stepper",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("step.label.set");
        assertThat(operation.path("op").asText()).isEqualTo("merge-by-key");
        assertThat(operation.path("resolvedPath").asText()).isEqualTo("steps[]/0");
        assertThat(operation.path("keyValue").asText()).isEqualTo("account");
        assertThat(operation.path("value").path("label").asText()).isEqualTo("Dados da conta");
        assertThat(result.patch().path("proposedConfig").path("steps").get(0).path("label").asText())
                .isEqualTo("Dados da conta");
    }

    @Test
    void compilesStepperDomainPatchWhenHandlerExists() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-stepper",
                payloadFromClasspathSnapshot("praxis-stepper"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "steps": [
                      { "id": "account", "label": "Conta" },
                      { "id": "review", "label": "Revisao" }
                    ],
                    "selectedIndex": 1
                  },
                  "plan": {
                    "operationId": "step.order.set",
                    "target": "review",
                    "input": {
                      "beforeStepId": "account"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-stepper",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: step-exists for step.order.set")
                .contains("validator declared without backend implementation: step-order-deterministic for step.order.set")
                .contains("validator declared without backend implementation: selected-index-preserved for step.order.set");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-stepper",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("op").asText()).isEqualTo("reorder-by-key");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("stepper-step-reorder");
        assertThat(operation.path("keyValue").asText()).isEqualTo("review");
        assertThat(operation.path("fromIndex").asInt()).isEqualTo(1);
        assertThat(operation.path("toIndex").asInt()).isZero();
        assertThat(operation.path("selectedIndexBefore").asInt()).isEqualTo(1);
        assertThat(operation.path("selectedIndexAfter").asInt()).isZero();
        assertThat(result.patch().path("proposedConfig").path("steps").get(0).path("id").asText())
                .isEqualTo("review");
        assertThat(result.patch().path("proposedConfig").path("steps").get(1).path("id").asText())
                .isEqualTo("account");
        assertThat(result.patch().path("proposedConfig").path("selectedIndex").asInt()).isZero();
    }

    @Test
    void compilesRichContentBlockAddFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-rich-content",
                payloadFromClasspathSnapshot("praxis-rich-content"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "document": {
                      "kind": "praxis.rich-content",
                      "version": "1.0.0",
                      "nodes": [
                        { "id": "hero", "type": "text", "text": "Intro" },
                        { "id": "footer", "type": "text", "text": "End" }
                      ]
                    }
                  },
                  "plan": {
                    "operationId": "block.add",
                    "input": {
                      "type": "text",
                      "node": {
                        "id": "body",
                        "text": "Body"
                      },
                      "afterBlockId": "hero"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: node-types-supported for block.add")
                .contains("validator declared without backend implementation: block-id-unique for block.add")
                .contains("validator declared without backend implementation: document-shape-canonical for block.add");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("block.add");
        assertThat(operation.path("op").asText()).isEqualTo("insert-rich-block");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("rich-content-block-add");
        assertThat(operation.path("keyValue").asText()).isEqualTo("body");
        assertThat(operation.path("insertedIndex").asInt()).isEqualTo(1);
        assertThat(result.patch().path("proposedConfig").path("document").path("nodes")).hasSize(3);
        assertThat(result.patch().path("proposedConfig").path("document").path("nodes").get(1).path("id").asText())
                .isEqualTo("body");
    }

    @Test
    void compilesRichContentMediaBlockUpdateFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-rich-content",
                payloadFromClasspathSnapshot("praxis-rich-content"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "document": {
                      "kind": "praxis.rich-content",
                      "version": "1.0.0",
                      "nodes": [
                        {
                          "id": "profile",
                          "type": "mediaBlock",
                          "title": "Old",
                          "subtitle": "Old subtitle",
                          "avatar": {
                            "name": "old",
                            "imageSrc": "/old.png"
                          }
                        }
                      ]
                    }
                  },
                  "plan": {
                    "operationId": "mediaBlock.update",
                    "target": "profile",
                    "input": {
                      "title": "New",
                      "subtitle": "New subtitle",
                      "avatarName": "person",
                      "avatarImageSrc": "/assets/person.png"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: block-exists for mediaBlock.update")
                .contains("validator declared without backend implementation: media-block-target-valid for mediaBlock.update")
                .contains("validator declared without backend implementation: unsafe-url-rejected for mediaBlock.update")
                .contains("validator declared without backend implementation: document-shape-canonical for mediaBlock.update");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("mediaBlock.update");
        assertThat(operation.path("op").asText()).isEqualTo("merge-rich-media-block");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("rich-content-media-block-update");
        assertThat(operation.path("keyValue").asText()).isEqualTo("profile");
        assertThat(operation.path("resolvedPath").asText()).isEqualTo("document.nodes[]/0");
        JsonNode updatedBlock = result.patch().path("proposedConfig").path("document").path("nodes").get(0);
        assertThat(updatedBlock.path("title").asText()).isEqualTo("New");
        assertThat(updatedBlock.path("subtitle").asText()).isEqualTo("New subtitle");
        assertThat(updatedBlock.path("avatar").path("name").asText()).isEqualTo("person");
        assertThat(updatedBlock.path("avatar").path("imageSrc").asText()).isEqualTo("/assets/person.png");
    }

    @Test
    void compilesRichContentLinkRemoveFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-rich-content",
                payloadFromClasspathSnapshot("praxis-rich-content"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "document": {
                      "kind": "praxis.rich-content",
                      "version": "1.0.0",
                      "nodes": [
                        { "id": "intro", "type": "text", "text": "Intro" },
                        { "id": "terms-link", "type": "link", "label": "Terms", "href": "/terms" },
                        { "id": "footer", "type": "text", "text": "End" }
                      ]
                    }
                  },
                  "plan": {
                    "operationId": "link.remove",
                    "target": "terms-link",
                    "input": {
                      "preserveLabelAsText": true
                    },
                    "confirmed": true
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: link-target-exists for link.remove")
                .contains("validator declared without backend implementation: destructive-removal-confirmed for link.remove")
                .contains("validator declared without backend implementation: document-shape-canonical for link.remove");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("link.remove");
        assertThat(operation.path("op").asText()).isEqualTo("replace-rich-link-with-text");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("rich-content-link-remove");
        assertThat(operation.path("keyValue").asText()).isEqualTo("terms-link");
        assertThat(operation.path("removedIndex").asInt()).isEqualTo(1);
        JsonNode replacement = result.patch().path("proposedConfig").path("document").path("nodes").get(1);
        assertThat(replacement.path("id").asText()).isEqualTo("terms-link-text");
        assertThat(replacement.path("type").asText()).isEqualTo("text");
        assertThat(replacement.path("text").asText()).isEqualTo("Terms");
        assertThat(result.patch().path("proposedConfig").path("document").path("nodes")).hasSize(3);
    }

    @Test
    void compilesRichContentTimelineItemAddFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-rich-content",
                payloadFromClasspathSnapshot("praxis-rich-content"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "document": {
                      "kind": "praxis.rich-content",
                      "version": "1.0.0",
                      "nodes": [
                        {
                          "id": "history",
                          "type": "timeline",
                          "items": [
                            { "id": "created", "title": "Created" },
                            { "id": "published", "title": "Published" }
                          ]
                        }
                      ]
                    }
                  },
                  "plan": {
                    "operationId": "timeline.item.add",
                    "target": "history",
                    "input": {
                      "timelineBlockId": "history",
                      "item": {
                        "id": "reviewed",
                        "title": "Reviewed"
                      },
                      "afterItemId": "created"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: block-exists for timeline.item.add")
                .contains("validator declared without backend implementation: timeline-target-valid for timeline.item.add")
                .contains("validator declared without backend implementation: timeline-item-id-unique for timeline.item.add");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("timeline.item.add");
        assertThat(operation.path("op").asText()).isEqualTo("insert-rich-timeline-item");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("rich-content-timeline-item-add");
        assertThat(operation.path("timelineBlockId").asText()).isEqualTo("history");
        assertThat(operation.path("keyValue").asText()).isEqualTo("reviewed");
        assertThat(operation.path("insertedIndex").asInt()).isEqualTo(1);
        JsonNode items = result.patch().path("proposedConfig").path("document").path("nodes").get(0).path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(1).path("id").asText()).isEqualTo("reviewed");
        assertThat(items.get(2).path("id").asText()).isEqualTo("published");
    }

    @Test
    void compilesRichContentTimelineItemUpdateFromClasspathRegistrySnapshot() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(
                "praxis-rich-content",
                payloadFromClasspathSnapshot("praxis-rich-content"));
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {
                    "document": {
                      "kind": "praxis.rich-content",
                      "version": "1.0.0",
                      "nodes": [
                        {
                          "id": "history",
                          "type": "timeline",
                          "items": [
                            { "id": "created", "title": "Created" },
                            { "id": "published", "title": "Published", "subtitle": "Draft" }
                          ]
                        }
                      ]
                    }
                  },
                  "plan": {
                    "operationId": "timeline.item.update",
                    "target": {
                      "timelineBlockId": "history",
                      "itemId": "published"
                    },
                    "input": {
                      "timelineBlockId": "history",
                      "field": "title",
                      "value": "Published live"
                    }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult validation = service.validateEditPlan(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(validation.valid()).isTrue();
        assertThat(validation.failures()).isEmpty();
        assertThat(validation.warnings())
                .contains("validator declared without backend implementation: block-exists for timeline.item.update")
                .contains("validator declared without backend implementation: timeline-item-exists for timeline.item.update")
                .contains("validator declared without backend implementation: timeline-item-field-supported for timeline.item.update");

        AgenticAuthoringManifestCompileResult result = service.compilePatch(
                "praxis-rich-content",
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.compiled()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.patch().path("manifestVersion").asText()).isEqualTo("1.0.0");
        JsonNode operation = result.patch().path("operations").get(0);
        assertThat(operation.path("operationId").asText()).isEqualTo("timeline.item.update");
        assertThat(operation.path("op").asText()).isEqualTo("merge-rich-timeline-item");
        assertThat(operation.path("domainHandler").asText()).isEqualTo("rich-content-timeline-item-update");
        assertThat(operation.path("timelineBlockId").asText()).isEqualTo("history");
        assertThat(operation.path("keyValue").asText()).isEqualTo("published");
        assertThat(operation.path("value").path("title").asText()).isEqualTo("Published live");
        JsonNode item = result.patch().path("proposedConfig").path("document").path("nodes").get(0).path("items").get(1);
        assertThat(item.path("title").asText()).isEqualTo("Published live");
        assertThat(item.path("subtitle").asText()).isEqualTo("Draft");
    }

    @Test
    void failsWhenOperationTargetKindIsNotDeclared() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(invalidTargetPayload());
        JsonNode request = objectMapper.readTree("""
                {
                  "config": {},
                  "plan": {
                    "operationId": "toolbar.visibility.set",
                    "input": { "visible": true }
                  }
                }
                """);

        AgenticAuthoringManifestValidationResult result = service.validateEditPlan(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.valid()).isFalse();
        assertThat(result.failures()).anyMatch(failure -> failure.contains("target.kind is not declared"));
    }

    @Test
    void rejectsValidatorThatHasNoBackendImplementation() throws Exception {
        AgenticAuthoringManifestService service = serviceWithPayload(unsupportedValidatorPayload());
        JsonNode request = objectMapper.readTree("""
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
                """);

        AgenticAuthoringManifestValidationResult result = service.validateEditPlan(
                COMPONENT_ID,
                objectMapper.treeToValue(request, AgenticAuthoringManifestEditPlanRequest.class));

        assertThat(result.valid()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.warnings())
                .contains("validator declared without backend implementation: not-implemented-validator for column.header.set");
    }

    private AgenticAuthoringManifestService serviceWithPayload(String payload) {
        return serviceWithPayload(COMPONENT_ID, payload);
    }

    private AgenticAuthoringManifestService serviceWithPayload(String componentId, String payload) {
        when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                eq("component_definition"),
                eq(componentId),
                eq("component-definition"),
                eq(Scope.SYSTEM),
                eq("GLOBAL")))
                .thenReturn(Optional.of(AiRegistry.builder().payload(payload).build()));
        AgenticAuthoringTargetResolverRegistry targetResolverRegistry = new AgenticAuthoringTargetResolverRegistry();
        return new AgenticAuthoringManifestService(
                repository,
                objectMapper,
                targetResolverRegistry,
                new AgenticAuthoringValidatorRegistry(targetResolverRegistry),
                new AgenticAuthoringEffectCompilerRegistry(objectMapper, targetResolverRegistry),
                new AgenticAuthoringManifestContractValidator());
    }

    private String payloadFromClasspathSnapshot(String componentId) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/ai-registry/registry-snapshot.json")) {
            assertThat(input).as("registry snapshot resource").isNotNull();
            JsonNode snapshot = objectMapper.readTree(input);
            JsonNode manifest = snapshot.path("components").path(componentId).path("authoringManifest");
            assertThat(manifest.isObject()).as("%s authoringManifest", componentId).isTrue();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.putObject("componentDefinition")
                    .putObject("jsonSchema")
                    .set("authoringManifest", manifest);
            return objectMapper.writeValueAsString(payload);
        }
    }

    private String validPayload() {
        return """
                {
                  "componentDefinition": {
                    "jsonSchema": {
                      "authoringManifest": {
                        "schemaVersion": "1.0.0",
                        "componentId": "praxis-table",
                        "manifestVersion": "1.0.0",
                        "editableTargets": [
                          { "kind": "column", "resolver": "column-by-field" },
                          { "kind": "field", "resolver": "field-by-name-or-label" },
                          { "kind": "expansion", "resolver": "expansion-config" }
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
                              }
                            },
                            "effects": [
                              { "kind": "merge-by-key", "path": "columns[]", "key": "field" }
                            ],
                            "affectedPaths": ["columns[].header"],
                            "preconditions": ["config-initialized", "target-exists"],
                            "validators": ["target-column-exists"],
                            "submissionImpact": "visual-only"
                          },
                          {
                            "operationId": "column.align.set",
                            "target": {
                              "kind": "column",
                              "resolver": "column-by-field",
                              "ambiguityPolicy": "fail",
                              "required": true
                            },
                            "inputSchema": {
                              "type": "object",
                              "required": ["align"],
                              "properties": {
                                "align": { "enum": ["start", "center", "end"] }
                              },
                              "additionalProperties": false
                            },
                            "effects": [
                              { "kind": "merge-by-key", "path": "columns[]", "key": "field" }
                            ],
                            "affectedPaths": ["columns[].align"],
                            "preconditions": ["config-initialized", "target-exists"],
                            "validators": ["target-column-exists"],
                            "submissionImpact": "visual-only"
                          },
                          {
                            "operationId": "field.label.set",
                            "target": {
                              "kind": "field",
                              "resolver": "field-by-name-or-label",
                              "ambiguityPolicy": "fail",
                              "required": true
                            },
                            "inputSchema": {
                              "type": "object",
                              "required": ["label"],
                              "properties": {
                                "label": { "type": "string" }
                              }
                            },
                            "effects": [
                              { "kind": "set-value", "path": "fieldMetadata[].label" }
                            ],
                            "affectedPaths": ["fieldMetadata[].label"],
                            "preconditions": ["config-initialized", "target-exists"],
                            "validators": ["field-exists"],
                            "submissionImpact": "visual-only"
                          },
                          {
                            "operationId": "expansion.detailSource.configure",
                            "target": {
                              "kind": "expansion",
                              "resolver": "expansion-config",
                              "ambiguityPolicy": "fail",
                              "required": false
                            },
                            "inputSchema": {
                              "type": "object",
                              "properties": {
                                "resourcePath": {
                                  "type": "object",
                                  "properties": {
                                    "path": { "type": "string" }
                                  }
                                }
                              }
                            },
                            "effects": [
                              { "kind": "merge-object", "path": "behavior.expansion.detail.source" }
                            ],
                            "affectedPaths": ["behavior.expansion.detail.source.resourcePath"],
                            "preconditions": ["config-initialized"],
                            "validators": ["remote-resource-binding-safe"],
                            "submissionImpact": "affects-remote-binding"
                          },
                          {
                            "operationId": "column.remove",
                            "target": {
                              "kind": "column",
                              "resolver": "column-by-field",
                              "ambiguityPolicy": "fail",
                              "required": true
                            },
                            "inputSchema": { "type": "object", "properties": {} },
                            "effects": [
                              { "kind": "remove-by-key", "path": "columns[]", "key": "field" }
                            ],
                            "affectedPaths": ["columns[]"],
                            "preconditions": ["config-initialized", "target-exists"],
                            "validators": ["destructive-removal-confirmation"],
                            "submissionImpact": "affects-schema-backed-data",
                            "destructive": true,
                            "requiresConfirmation": true
                          }
                        ],
                        "validators": [
                          { "validatorId": "target-column-exists" },
                          { "validatorId": "field-exists" },
                          { "validatorId": "remote-resource-binding-safe" },
                          { "validatorId": "destructive-removal-confirmation" }
                        ]
                      }
                    }
                  }
                }
                """;
    }

    private String unsupportedValidatorPayload() {
        return validPayload().replace(
                "{ \"validatorId\": \"target-column-exists\" }",
                "{ \"validatorId\": \"target-column-exists\" }, { \"validatorId\": \"not-implemented-validator\" }")
                .replace(
                        "\"validators\": [\"target-column-exists\"],",
                        "\"validators\": [\"target-column-exists\", \"not-implemented-validator\"],");
    }

    private String invalidTargetPayload() {
        return """
                {
                  "componentDefinition": {
                    "jsonSchema": {
                      "authoringManifest": {
                        "schemaVersion": "1.0.0",
                        "componentId": "praxis-table",
                        "manifestVersion": "1.0.0",
                        "editableTargets": [
                          { "kind": "toolbarAction", "resolver": "action-by-id" }
                        ],
                        "operations": [
                          {
                            "operationId": "toolbar.visibility.set",
                            "target": {
                              "kind": "toolbar",
                              "resolver": "toolbar-config",
                              "ambiguityPolicy": "fail",
                              "required": false
                            },
                            "inputSchema": {
                              "type": "object",
                              "required": ["visible"],
                              "properties": {
                                "visible": { "type": "boolean" }
                              }
                            },
                            "effects": [
                              { "kind": "set-value", "path": "toolbar.visible" }
                            ],
                            "affectedPaths": ["toolbar.visible"],
                            "preconditions": ["config-initialized"],
                            "validators": [],
                            "submissionImpact": "visual-only"
                          }
                        ],
                        "validators": []
                      }
                    }
                  }
                }
                """;
    }
}
