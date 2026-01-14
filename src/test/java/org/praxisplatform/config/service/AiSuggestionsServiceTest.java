package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiCapability;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiSuggestion;
import org.praxisplatform.config.dto.AiSuggestionsRequest;
import org.praxisplatform.config.dto.AiSuggestionsResponse;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSuggestionsServiceTest {

    private AiSuggestionsService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new AiSuggestionsService(objectMapper);
    }

    @Test
    void shouldSuggestRightAlignForCurrency() {
        // Given
        ObjectNode config = objectMapper.createObjectNode();
        ArrayNode columns = config.putArray("columns");
        ObjectNode priceCol = columns.addObject();
        priceCol.put("field", "price");
        priceCol.put("header", "Price");
        priceCol.put("type", "currency");
        // align is missing

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(wrapConfig(config));
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities("columns[].align"))
                .build();

        // When
        AiSuggestionsResponse response = service.suggest(request, context);

        // Then
        boolean found = response.getSuggestions().stream()
                .anyMatch(s -> "table.align.right.price".equals(s.getId()));
        
        assertTrue(found, "Should suggest right alignment for currency column");
    }

    @Test
    void shouldSuggestBooleanFormat() {
        // Given
        ObjectNode config = objectMapper.createObjectNode();
        ArrayNode columns = config.putArray("columns");
        ObjectNode activeCol = columns.addObject();
        activeCol.put("field", "isActive");
        activeCol.put("header", "Is Active");
        activeCol.put("type", "boolean");
        // format is missing

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(wrapConfig(config));
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities("columns[].format"))
                .build();

        // When
        AiSuggestionsResponse response = service.suggest(request, context);

        // Then
        boolean found = response.getSuggestions().stream()
                .anyMatch(s -> "table.format.boolean.isActive".equals(s.getId()));
        assertTrue(found, "Should suggest boolean format for 'isActive'");
    }

    @Test
    void shouldSuggestCurrencyFormatFromSchemaFields() {
        // Given
        ObjectNode config = objectMapper.createObjectNode();
        ArrayNode columns = config.putArray("columns");
        ObjectNode amountCol = columns.addObject();
        amountCol.put("field", "amount");
        amountCol.put("header", "Amount");
        amountCol.put("type", "number");

        ObjectNode currentState = wrapConfig(config);
        ArrayNode schemaFields = currentState.putArray("schemaFields");
        schemaFields.addObject()
                .put("name", "amount")
                .put("controlType", "currency")
                .put("numericFormat", "currency");
        addCapabilities(currentState, "columns[].format");

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(currentState);
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities("columns[].format"))
                .build();

        // When
        AiSuggestionsResponse response = service.suggest(request, context);

        // Then
        boolean found = response.getSuggestions().stream()
                .anyMatch(s -> "table.format.currency.amount".equals(s.getId()));
        assertTrue(found, "Should suggest currency format when schemaFields controlType is currency");
    }

    @Test
    void shouldSuggestStickyColumnForWideTables() {
        // Given
        ObjectNode config = objectMapper.createObjectNode();
        ArrayNode columns = config.putArray("columns");
        for (int i = 0; i < 10; i++) {
            ObjectNode col = columns.addObject();
            col.put("field", "col" + i);
            col.put("visible", true);
        }

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(wrapConfig(config));
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities("columns[].sticky"))
                .build();

        // When
        AiSuggestionsResponse response = service.suggest(request, context);

        // Then
        boolean found = response.getSuggestions().stream()
                .anyMatch(s -> "table.sticky.first".equals(s.getId()));
        assertTrue(found, "Should suggest sticky column for wide table");
    }

    @Test
    void shouldSuggestSelectionAndExportForData() {
        // Given
        ObjectNode config = objectMapper.createObjectNode();
        config.putObject("behavior").putObject("selection").put("enabled", false);
        config.putObject("export").put("enabled", false);
        config.putArray("columns").addObject().put("field", "id");

        ObjectNode dataProfile = objectMapper.createObjectNode();
        dataProfile.put("rowCount", 100);

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(wrapConfig(config));
        request.setDataProfile(dataProfile);
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities("behavior.selection.enabled", "export.enabled"))
                .build();

        // When
        AiSuggestionsResponse response = service.suggest(request, context);

        // Then
        Set<String> ids = response.getSuggestions().stream()
                .map(AiSuggestion::getId)
                .collect(Collectors.toSet());

        assertTrue(ids.contains("table.selection.enable"), "Should suggest selection");
        assertTrue(ids.contains("table.export.enable"), "Should suggest export");
    }

    @Test
    void shouldIncludeStructuredBadgeSuggestionWhenValuesAvailable() {
        // Given
        ObjectNode config = objectMapper.createObjectNode();
        ArrayNode columns = config.putArray("columns");
        ObjectNode classeCol = columns.addObject();
        classeCol.put("field", "classe");
        classeCol.put("header", "Classe");

        ObjectNode dataProfile = objectMapper.createObjectNode();
        dataProfile.put("rowCount", 10);
        ObjectNode profileColumns = dataProfile.putObject("columns");
        ObjectNode classeStats = profileColumns.putObject("classe");
        classeStats.put("inferredType", "string");
        classeStats.put("cardinality", 3);
        ArrayNode topValues = classeStats.putArray("topValues");
        topValues.add("VILAO");
        topValues.add("ENTIDADE");
        topValues.add("ORGANIZACAO");

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(wrapConfig(config));
        request.setDataProfile(dataProfile);
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities(
                        "columns[].renderer.type",
                        "columns[].renderer.badge",
                        "columns[].conditionalRenderers"))
                .build();

        // When
        AiSuggestionsResponse response = service.suggest(request, context);

        // Then
        AiSuggestion suggestion = response.getSuggestions().stream()
                .filter(s -> "table.renderer.badge.classe".equals(s.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(suggestion, "Badge suggestion should be present");
        assertNotNull(suggestion.getPatch(), "Badge suggestion should include patch");
        assertNotNull(suggestion.getContextHints(), "Badge suggestion should include context hints");
        assertEquals("classe", suggestion.getPatch().at("/columns/0/field").asText());
        assertTrue(suggestion.getContextHints().has("values"), "Context hints should include values");
    }

    @Test
    void shouldAvoidQuotingBooleanBadgeValuesWhenExplicitTypeIsBoolean() {
        ObjectNode config = objectMapper.createObjectNode();
        ArrayNode columns = config.putArray("columns");
        ObjectNode activeCol = columns.addObject();
        activeCol.put("field", "active");
        activeCol.put("header", "Ativo");
        activeCol.put("type", "boolean");

        ObjectNode dataProfile = objectMapper.createObjectNode();
        dataProfile.put("rowCount", 5);
        ObjectNode profileColumns = dataProfile.putObject("columns");
        ObjectNode activeStats = profileColumns.putObject("active");
        activeStats.put("cardinality", 2);
        ArrayNode topValues = activeStats.putArray("topValues");
        topValues.add("true");
        topValues.add("false");

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(wrapConfig(config));
        request.setDataProfile(dataProfile);
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities(
                        "columns[].renderer.type",
                        "columns[].renderer.badge",
                        "columns[].conditionalRenderers"))
                .build();

        AiSuggestionsResponse response = service.suggest(request, context);

        AiSuggestion suggestion = response.getSuggestions().stream()
                .filter(s -> "table.renderer.badge.active".equals(s.getId()))
                .findFirst()
                .orElse(null);

        assertNotNull(suggestion, "Badge suggestion should be present");
        assertNotNull(suggestion.getPatch(), "Badge suggestion should include patch");
        String condition = suggestion.getPatch()
                .at("/columns/0/conditionalRenderers/0/condition")
                .asText();
        assertEquals("active == true", condition);
    }

    private ObjectNode wrapConfig(ObjectNode config) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("config", config);
        return root;
    }

    private void addCapabilities(ObjectNode state, String... paths) {
        ArrayNode caps = state.putArray("capabilities");
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            caps.addObject().put("path", path);
        }
    }

    private JsonNode createDefinitionWithCapabilities(String... paths) {
        ObjectNode def = objectMapper.createObjectNode();
        ArrayNode caps = def.putArray("capabilities");
        for (String path : paths) {
            ObjectNode cap = caps.addObject();
            cap.put("path", path);
        }
        return def;
    }

    @Test
    void shouldSkipCurrencySuggestionWhenRuntimeDisallowsFormat() {
        // Given
        ObjectNode config = objectMapper.createObjectNode();
        ArrayNode columns = config.putArray("columns");
        ObjectNode amountCol = columns.addObject();
        amountCol.put("field", "amount");
        amountCol.put("header", "Amount");
        amountCol.put("type", "number");

        ObjectNode currentState = wrapConfig(config);
        ArrayNode schemaFields = currentState.putArray("schemaFields");
        schemaFields.addObject()
                .put("name", "amount")
                .put("controlType", "currency")
                .put("numericFormat", "currency");
        addCapabilities(currentState, "columns[].align");

        AiSuggestionsRequest request = new AiSuggestionsRequest();
        request.setCurrentState(currentState);
        request.setComponentId("praxis-table");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-table")
                .componentDefinition(createDefinitionWithCapabilities("columns[].format", "columns[].align"))
                .build();

        // When
        AiSuggestionsResponse response = service.suggest(request, context);

        // Then
        boolean found = response.getSuggestions().stream()
                .anyMatch(s -> "table.format.currency.amount".equals(s.getId()));
        assertTrue(!found, "Should not suggest currency format when runtime lacks columns[].format capability");
    }
}
