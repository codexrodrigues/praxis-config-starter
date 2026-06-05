package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringManifestContractValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringManifestContractValidator validator =
            new AgenticAuthoringManifestContractValidator();

    @Test
    void shouldAcceptMissingManifestBecauseAuthoringContractIsOptionalOnIngestion() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(MissingNode.getInstance())).isEmpty();
    }

    @Test
    void shouldRejectNonObjectManifest() throws Exception {
        JsonNode manifest = objectMapper.readTree("[]");

        List<String> failures = validator.validate(manifest);

        assertThat(failures).containsExactly("authoringManifest must be an object");
    }

    @Test
    void shouldAcceptExecutableManifestShape() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "schemaVersion": "1.0.0",
                  "componentId": "praxis-table",
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "column", "resolver": "column-by-field" }
                  ],
                  "validators": [
                    { "validatorId": "target-column-exists" }
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
                      "preconditions": ["config-initialized", "target-exists"],
                      "validators": ["target-column-exists"],
                      "effects": [
                        { "kind": "merge-by-key", "path": "columns[]", "key": "field" }
                      ],
                      "affectedPaths": ["columns[].header"],
                      "submissionImpact": "visual-only"
                    }
                  ]
                }
                """);

        assertThat(validator.validate(manifest)).isEmpty();
    }

    @Test
    void shouldAcceptPresentationAffordancesWhenCatalogShapeIsValid() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "schemaVersion": "1.0.0",
                  "componentId": "praxis-table",
                  "manifestVersion": "1.0.0",
                  "editableTargets": [
                    { "kind": "column", "resolver": "column-by-field" }
                  ],
                  "validators": [
                    { "validatorId": "target-column-exists" }
                  ],
                  "operations": [
                    {
                      "operationId": "column.header.set",
                      "target": {
                        "kind": "column",
                        "resolver": "column-by-field",
                        "required": true
                      },
                      "preconditions": ["config-initialized"],
                      "validators": ["target-column-exists"],
                      "effects": [
                        { "kind": "merge-by-key", "path": "columns[]", "key": "field" }
                      ],
                      "affectedPaths": ["columns[].header"],
                      "submissionImpact": "visual-only"
                    }
                  ],
                  "presentationAffordances": {
                    "version": "1.0.0",
                    "kind": "praxis.ai-authoring.presentation-affordance-catalog",
                    "defaultTargetKind": "column",
                    "sourceRef": "ai_registry:praxis-table:authoringManifest.presentationAffordances",
                    "affordances": [
                      {
                        "id": "column.renderer.badge",
                        "targetKind": "column",
                        "category": "renderer",
                        "description": "Render status values as a badge.",
                        "options": ["soft"],
                        "appliesToTypes": ["string"],
                        "unknownCompatible": false
                      }
                    ]
                  }
                }
                """);

        assertThat(validator.validate(manifest)).isEmpty();
    }

    @Test
    void shouldRejectInvalidPresentationAffordanceCatalogShape() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "editableTargets": [
                    { "kind": "column", "resolver": "column-by-field" }
                  ],
                  "validators": [
                    { "validatorId": "target-column-exists" }
                  ],
                  "operations": [
                    {
                      "operationId": "column.header.set",
                      "target": {
                        "kind": "column",
                        "resolver": "column-by-field",
                        "required": true
                      },
                      "preconditions": ["config-initialized"],
                      "validators": ["target-column-exists"],
                      "effects": [
                        { "kind": "merge-by-key", "path": "columns[]", "key": "field" }
                      ],
                      "affectedPaths": ["columns[].header"],
                      "submissionImpact": "visual-only"
                    }
                  ],
                  "presentationAffordances": {
                    "version": "",
                    "defaultTargetKind": "column",
                    "sourceRef": "",
                    "affordances": [
                      {
                        "id": "",
                        "targetKind": "",
                        "category": "",
                        "description": "",
                        "options": ["soft", ""],
                        "appliesToTypes": [],
                        "unknownCompatible": "false"
                      }
                    ]
                  }
                }
                """);

        List<String> failures = validator.validate(manifest);

        assertThat(failures)
                .contains(
                        "presentationAffordances.version is required",
                        "presentationAffordances.sourceRef is required",
                        "presentationAffordances.affordances[].id is required",
                        "presentationAffordances.affordances[].targetKind is required",
                        "presentationAffordances.affordances[].category is required",
                        "presentationAffordances.affordances[].description is required",
                        "presentationAffordances.affordances[].options must contain only non-blank strings",
                        "presentationAffordances.affordances[].appliesToTypes must be a non-empty array",
                        "presentationAffordances.affordances[].unknownCompatible must be boolean");
    }

    @Test
    void shouldAggregateContractShapeFailures() {
        ObjectNode manifest = objectMapper.createObjectNode();
        ObjectNode operation = manifest.putArray("operations").addObject();
        operation.put("operationId", "toolbar.visibility.set");
        operation.putObject("target")
                .put("kind", "toolbar")
                .put("resolver", "toolbar-config");
        operation.putArray("validators").add("missing-validator");
        operation.putArray("effects").addObject()
                .put("kind", "set-value")
                .put("path", "toolbar.visible");
        operation.putArray("affectedPaths").add("toolbar.visible");
        operation.put("submissionImpact", "visual-only");

        List<String> failures = validator.validate(manifest);

        assertThat(failures)
                .contains(
                        "editableTargets must be an array",
                        "validators must be an array",
                        "operation target.kind is not declared in editableTargets: toolbar.visibility.set -> toolbar",
                        "operation preconditions are required: toolbar.visibility.set",
                        "operation references unknown validator: toolbar.visibility.set -> missing-validator");
    }

    @Test
    void shouldRequireConfirmationMetadataForDestructiveOperations() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "editableTargets": [
                    { "kind": "column", "resolver": "column-by-field" }
                  ],
                  "validators": [
                    { "validatorId": "destructive-removal-confirmation" }
                  ],
                  "operations": [
                    {
                      "operationId": "column.remove",
                      "target": {
                        "kind": "column",
                        "resolver": "column-by-field",
                        "required": true
                      },
                      "preconditions": ["config-initialized", "target-exists"],
                      "validators": ["destructive-removal-confirmation"],
                      "effects": [
                        { "kind": "remove-by-key", "path": "columns[]", "key": "field" }
                      ],
                      "affectedPaths": ["columns[]"],
                      "submissionImpact": "affects-schema-backed-data",
                      "destructive": true
                    }
                  ]
                }
                """);

        List<String> failures = validator.validate(manifest);

        assertThat(failures).contains("destructive operation requires confirmation: column.remove");
    }
}
