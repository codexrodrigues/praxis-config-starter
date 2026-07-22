package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles a transient Structured Outputs projection from a canonical manifest schema.
 *
 * <p>The projection is deliberately lossy only where a provider cannot express the canonical
 * JSON Schema subset. The manifest remains unchanged and is always the validation authority.
 */
public final class AgenticAuthoringProviderSchemaCompiler {

    private final ObjectMapper objectMapper;

    public AgenticAuthoringProviderSchemaCompiler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode compileInputSchema(JsonNode source) {
        return compileSchema(source, false);
    }

    public ObjectNode compileOperationSchema(JsonNode operation) {
        ObjectNode variant = objectMapper.createObjectNode();
        variant.put("type", "object");
        variant.put("additionalProperties", false);
        variant.putArray("required").add("operationId").add("input").add("target").add("confirmed");
        ObjectNode properties = variant.putObject("properties");
        properties.putObject("operationId").put("type", "string")
                .put("const", operation.path("operationId").asText(""));
        JsonNode inputSchema = operation.path("inputSchema");
        properties.set("input", inputSchema.isObject() ? compileInputSchema(inputSchema) : emptyObjectSchema());
        properties.set("target", nullableTargetSchema());
        properties.putObject("confirmed").putArray("type").add("boolean").add("null");
        return variant;
    }

    public ObjectNode compileEditPlanSchema(String schemaVersion, String componentId, List<JsonNode> operations) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("schemaVersion").add("componentId").add("operations");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("schemaVersion").put("type", "string").put("const", schemaVersion);
        properties.putObject("componentId").put("type", "string").put("const", componentId);
        ObjectNode outputOperations = properties.putObject("operations");
        outputOperations.put("type", "array").put("minItems", 1).put("maxItems", 8);
        if (operations.size() == 1) {
            outputOperations.set("items", compileOperationSchema(operations.get(0)));
        } else {
            ArrayNode variants = outputOperations.putObject("items").putArray("anyOf");
            operations.forEach(operation -> variants.add(compileOperationSchema(operation)));
        }
        return schema;
    }

    public JsonNode decodeCompatibilityValues(JsonNode plan, JsonNode manifest) {
        if (!(plan != null && plan.isObject())) return plan;
        ObjectNode canonical = plan.deepCopy();
        for (JsonNode planOperation : canonical.path("operations")) {
            if (!(planOperation instanceof ObjectNode operation)) continue;
            if (operation.path("target").isNull()) operation.remove("target");
            else if (operation.path("target").isObject() && operation.path("target").size() == 1
                    && operation.path("target").path("value").isTextual()) {
                operation.put("target", operation.path("target").path("value").asText());
            }
            if (operation.path("confirmed").isNull()) operation.remove("confirmed");
            JsonNode manifestOperation = manifestOperation(manifest, operation.path("operationId").asText(""));
            if (manifestOperation != null && operation.path("input") instanceof ObjectNode input) {
                removeCompatibilityValues(input, manifestOperation.path("inputSchema"));
            }
        }
        return canonical;
    }

    private ObjectNode compileSchema(JsonNode source, boolean encodeFreeFormValue) {
        ObjectNode schema = source != null && source.isObject() ? source.deepCopy() : objectMapper.createObjectNode();
        for (String unsupported : List.of("$schema", "default", "examples", "allOf", "not", "dependentRequired",
                "dependentSchemas", "if", "then", "else", "patternProperties", "minProperties", "maxProperties")) {
            schema.remove(unsupported);
        }
        if (encodeFreeFormValue && requiresJsonTextEncoding(schema)) return encodedJsonTextSchema(schema);
        if (schema.path("properties").isObject() || "object".equals(schema.path("type").asText())) {
            schema.put("type", "object");
            ObjectNode properties = schema.path("properties") instanceof ObjectNode declared ? declared : schema.putObject("properties");
            Set<String> originallyRequired = new LinkedHashSet<>();
            schema.path("required").forEach(value -> originallyRequired.add(value.asText("")));
            List<String> names = new ArrayList<>();
            properties.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                ObjectNode child = compileSchema(properties.path(name), true);
                if (!originallyRequired.contains(name)) makeNullable(child);
                properties.set(name, child);
            }
            ArrayNode required = schema.putArray("required");
            names.forEach(required::add);
            schema.put("additionalProperties", false);
        }
        if (schema.path("items").isObject()) schema.set("items", compileSchema(schema.path("items"), true));
        JsonNode oneOf = schema.remove("oneOf");
        if (oneOf != null && oneOf.isArray() && !schema.has("anyOf")) schema.set("anyOf", oneOf);
        if (schema.path("anyOf").isArray()) {
            if (isPresenceOnlyUnion(schema.path("anyOf"))) schema.remove("anyOf");
            else {
                ArrayNode variants = objectMapper.createArrayNode();
                schema.path("anyOf").forEach(variant -> variants.add(compileSchema(variant, true)));
                schema.set("anyOf", variants);
            }
        }
        inferTypeFromEnum(schema);
        inferTypeFromConst(schema);
        return schema;
    }

    private ObjectNode encodedJsonTextSchema(JsonNode schema) {
        ObjectNode encoded = objectMapper.createObjectNode();
        encoded.put("type", "string");
        String description = schema.path("description").asText("");
        String kind = isFreeFormArraySchema(schema) ? "array" : "object";
        encoded.put("description", (description.isBlank() ? "Canonical " + kind + "." : description + " ")
                + "Return this " + kind + " as compact JSON text for provider transport; Praxis decodes it before canonical manifest validation.");
        return encoded;
    }

    private ObjectNode nullableTargetSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode variants = schema.putArray("anyOf");
        variants.addObject().put("type", "string");
        ObjectNode object = variants.addObject().put("type", "object").put("additionalProperties", false);
        object.putArray("required").add("value");
        object.putObject("properties").putObject("value").put("type", "string");
        variants.addObject().put("type", "null");
        return schema;
    }

    private ObjectNode emptyObjectSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object").put("additionalProperties", false);
        schema.putObject("properties");
        schema.putArray("required");
        return schema;
    }

    private void removeCompatibilityValues(ObjectNode value, JsonNode schema) {
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(name -> required.add(name.asText("")));
        List<String> fields = new ArrayList<>();
        value.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode child = value.path(field);
            JsonNode childSchema = schema.path("properties").path(field);
            if (child.isNull() && !required.contains(field)) value.remove(field);
            else if (child.isTextual() && requiresJsonTextEncoding(childSchema)) decodeJsonText(child.asText(""), childSchema).ifPresent(decoded -> value.set(field, decoded));
            else if (child instanceof ObjectNode childObject && childSchema.isObject()) removeCompatibilityValues(childObject, childSchema);
            else if (child instanceof ArrayNode array && childSchema.path("items").isObject()) {
                JsonNode itemSchema = childSchema.path("items");
                for (int i = 0; i < array.size(); i++) {
                    JsonNode item = array.get(i);
                    if (item.isTextual() && requiresJsonTextEncoding(itemSchema)) {
                        java.util.Optional<JsonNode> decoded = decodeJsonText(item.asText(""), itemSchema);
                        if (decoded.isPresent()) array.set(i, decoded.get());
                    }
                    else if (item instanceof ObjectNode itemObject) removeCompatibilityValues(itemObject, itemSchema);
                }
            }
        }
    }

    private java.util.Optional<JsonNode> decodeJsonText(String value, JsonNode canonicalSchema) {
        if (value == null || value.isBlank()) return java.util.Optional.empty();
        try {
            JsonNode decoded = objectMapper.readTree(value);
            return (isFreeFormObjectSchema(canonicalSchema) && decoded.isObject()) || (isFreeFormArraySchema(canonicalSchema) && decoded.isArray())
                    ? java.util.Optional.of(decoded) : java.util.Optional.empty();
        } catch (Exception ignored) { return java.util.Optional.empty(); }
    }

    private boolean requiresJsonTextEncoding(JsonNode schema) {
        return isFreeFormObjectSchema(schema) || isFreeFormArraySchema(schema) || isUnconstrainedSchema(schema);
    }
    private boolean isFreeFormObjectSchema(JsonNode schema) { return "object".equals(schema.path("type").asText("")) && (!schema.path("properties").isObject() || schema.path("properties").isEmpty()); }
    private boolean isFreeFormArraySchema(JsonNode schema) { return "array".equals(schema.path("type").asText("")) && (!schema.path("items").isObject() || schema.path("items").isEmpty()); }
    private boolean isUnconstrainedSchema(JsonNode schema) {
        return !schema.has("type") && !schema.has("enum") && !schema.has("const")
                && !schema.has("properties") && !schema.has("items")
                && !schema.has("anyOf") && !schema.has("oneOf");
    }
    private boolean isPresenceOnlyUnion(JsonNode union) {
        if (!union.isArray() || union.isEmpty()) return false;
        for (JsonNode variant : union) if (!variant.isObject() || variant.size() != 1 || !variant.path("required").isArray() || variant.path("required").isEmpty()) return false;
        return true;
    }
    private void makeNullable(ObjectNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) schema.putArray("type").add(type.asText()).add("null");
        else if (type instanceof ArrayNode types && !types.toString().contains("\"null\"")) types.add("null");
        if (schema.path("enum") instanceof ArrayNode values && !values.toString().contains("null")) values.addNull();
    }
    private void inferTypeFromEnum(ObjectNode schema) {
        if (schema.has("type") || !schema.path("enum").isArray()) return;
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode value : schema.path("enum")) types.add(value.isTextual() ? "string" : value.isBoolean() ? "boolean" : value.isIntegralNumber() ? "integer" : value.isNumber() ? "number" : value.isNull() ? "null" : "");
        types.remove(""); ArrayNode type = schema.putArray("type"); types.forEach(type::add);
    }
    private void inferTypeFromConst(ObjectNode schema) {
        if (schema.has("type") || !schema.has("const")) return;
        JsonNode value = schema.path("const");
        if (value.isTextual()) schema.put("type", "string"); else if (value.isBoolean()) schema.put("type", "boolean"); else if (value.isIntegralNumber()) schema.put("type", "integer"); else if (value.isNumber()) schema.put("type", "number"); else if (value.isNull()) schema.put("type", "null");
    }
    private JsonNode manifestOperation(JsonNode manifest, String id) { for (JsonNode operation : manifest.path("operations")) if (id.equals(operation.path("operationId").asText(""))) return operation; return null; }
}
