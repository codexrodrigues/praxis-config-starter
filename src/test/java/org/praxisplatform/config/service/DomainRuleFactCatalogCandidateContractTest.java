package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Executable G1 specification of the candidate wire contract.
 * This test does not register v2 in DomainRuleService or certify runtime admission.
 */
@Tag("unit")
class DomainRuleFactCatalogCandidateContractTest {
    private static final Path CONTRACTS = Path.of("docs", "domain-rules", "contracts");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    // Candidate DTO signatures; production replacement and consumer migration belong to F4.
    record Catalog(String schemaVersion, List<Operation> operations) {}
    record Operation(String operationKey, JsonNode snapshotSchema, List<Fact> facts) {}
    record Fact(String schemaPointer, Map<String, String> labels, Map<String, String> descriptions,
                String providerRef, List<String> evidenceRefs, String sensitivity, String redaction) {}

    @Test
    void candidateRoundTripPreservesObjectsCollectionsGovernanceAndOperationScope() throws Exception {
        JsonNode source = catalog();
        assertThat(errors(source)).isEmpty();
        Catalog dto = MAPPER.treeToValue(source, Catalog.class);
        assertThat(MAPPER.<JsonNode>valueToTree(dto)).isEqualTo(source);
        assertThat(snapshotErrors(source, values())).isEmpty();
        assertThat(dto.operations().getFirst().facts())
                .anyMatch(fact -> fact.schemaPointer().contains("/items/properties/"));
    }

    @TestFactory
    Stream<DynamicTest> rejectsMalformedCatalogs() {
        return Stream.of(
                mutation("wrong literal", n -> n.put("schemaVersion", "praxis.domain-rules.fact-catalog/v1")),
                mutation("legacy version", n -> n.put("schemaVersion", "praxis.domain-rule-fact-catalog.v1")),
                mutation("unknown version", n -> n.put("schemaVersion", "praxis.domain-rule-fact-catalog.v99")),
                mutation("missing operation", n -> n.remove("operations")),
                mutation("duplicate operation", n -> ((ArrayNode) n.get("operations")).add(n.at("/operations/0").deepCopy())),
                mutation("empty operation", n -> op(n).put("operationKey", "")),
                mutation("no evidence", n -> fact(n).remove("evidenceRefs")),
                mutation("empty evidence", n -> fact(n).putArray("evidenceRefs")),
                mutation("no labels", n -> fact(n).remove("labels")),
                mutation("no descriptions", n -> fact(n).remove("descriptions")),
                mutation("no provider", n -> fact(n).remove("providerRef")),
                mutation("no sensitivity", n -> fact(n).remove("sensitivity")),
                mutation("no redaction", n -> fact(n).remove("redaction")),
                mutation("unknown sensitivity", n -> fact(n).put("sensitivity", "PUBLIC")),
                mutation("unknown redaction", n -> fact(n).put("redaction", "PASS")),
                mutation("dangling schema pointer", n -> fact(n).put("schemaPointer", "#/properties/absent")),
                mutation("pointer to annotation", n -> fact(n).put("schemaPointer", "#/properties/draft/required")),
                mutation("duplicate descriptor", n -> ((ArrayNode) op(n).get("facts")).add(fact(n).deepCopy())),
                mutation("missing item descriptor", n -> ((ArrayNode) op(n).get("facts")).remove(6)),
                mutation("legacy path", n -> fact(n).put("path", "draft.dtini")),
                mutation("redundant nullable", n -> fact(n).put("nullable", true)),
                mutation("redundant valueType", n -> fact(n).put("valueType", "date")),
                mutation("remote ref", n -> shape(n).put("$ref", "https://invalid.example/schema")),
                mutation("recursive ref", n -> shape(n).put("$ref", "#")),
                mutation("open object", n -> shape(n).put("additionalProperties", true)),
                mutation("nullable snapshot root", n -> shape(n).putArray("type").add("object").add("null")),
                mutation("null outside enum type", n -> ((ObjectNode) n.at("/operations/0/snapshotSchema/properties/draft/properties/dtini")).putArray("enum").addNull()),
                mutation("required unknown property", n -> ((ArrayNode) shape(n).get("required")).add("ghost")),
                mutation("unbounded string", n -> ((ObjectNode) n.at("/operations/0/snapshotSchema/properties/draft/properties/dtini")).remove("maxLength")),
                mutation("unsupported type", n -> ((ObjectNode) n.at("/operations/0/snapshotSchema/properties/draft")).put("type", "object-array")),
                mutation("array without items", n -> array(n).remove("items")),
                mutation("array above engine limit", n -> array(n).put("maxItems", 10001)),
                mutation("inverted cardinality", n -> { array(n).put("minItems", 10); array(n).put("maxItems", 2); }),
                mutation("inverted number bounds", n -> { var days = (ObjectNode) array(n).at("/items/properties/days"); days.put("minimum", 20); days.put("maximum", 2); }),
                mutation("empty properties with impossible bound", n -> shape(n).put("maxProperties", 1))
        ).map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            ObjectNode candidate = catalog().deepCopy();
            test.change().accept(candidate);
            assertThat(errors(candidate)).as(test.name()).isNotEmpty();
        }));
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidProviderValuesIndependentlyOfCatalogShape() {
        return Stream.of(
                mutation("missing required", n -> ((ObjectNode) n.get("draft")).remove("dtini")),
                mutation("null forbidden", n -> ((ObjectNode) n.get("draft")).putNull("dtini")),
                mutation("missing nullable still missing", n -> ((ObjectNode) n.get("draft")).remove("dtfim")),
                mutation("date is not number", n -> ((ObjectNode) n.get("draft")).put("dtini", 123)),
                mutation("invalid civil date", n -> ((ObjectNode) n.get("draft")).put("dtini", "2024-02-30")),
                mutation("datetime is not civil date", n -> ((ObjectNode) n.get("draft")).put("dtini", "2024-02-28T00:00:00Z")),
                mutation("object item required", n -> ((ArrayNode) n.at("/neighborhood/periods")).set(0, MAPPER.getNodeFactory().textNode("flat"))),
                mutation("nested wrong type", n -> ((ObjectNode) n.at("/neighborhood/periods/0")).put("days", "2")),
                mutation("nested missing field", n -> ((ObjectNode) n.at("/neighborhood/periods/0")).remove("dtini")),
                mutation("unknown property", n -> ((ObjectNode) n.get("draft")).put("inicio", 1)),
                mutation("current unavailable in create", n -> n.putObject("current")),
                mutation("collection cardinality", n -> { ArrayNode a=(ArrayNode)n.at("/neighborhood/periods"); JsonNode item=a.get(0).deepCopy(); for(int i=0;i<100;i++)a.add(item.deepCopy()); })
        ).map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            ObjectNode value=values().deepCopy(); test.change().accept(value);
            assertThat(snapshotErrors(catalog(), value)).as(test.name()).isNotEmpty();
        }));
    }

    @Test
    void explicitNullAndEmptyCollectionAreValidAndDistinctFromMissing() throws Exception {
        ObjectNode value=values().deepCopy();
        value.withObject("/draft").putNull("dtfim");
        ((ArrayNode)value.at("/neighborhood/periods")).removeAll();
        assertThat(snapshotErrors(catalog(),value)).isEmpty();
    }

    @Test
    void nullableEnumExplicitlyIncludesNull() throws Exception {
        ObjectNode candidate=catalog().deepCopy();
        ((ObjectNode)candidate.at("/operations/0/snapshotSchema/properties/draft/properties/dtfim"))
                .putArray("enum").add("2024-03-01").addNull();
        ObjectNode value=values().deepCopy();
        value.withObject("/draft").putNull("dtfim");
        assertThat(errors(candidate)).isEmpty();
        assertThat(snapshotErrors(candidate,value)).isEmpty();
    }

    @Test
    void typedMapAndEscapedSchemaPointerValidateActualDateValues() throws Exception {
        ObjectNode candidate=catalog().deepCopy();
        ObjectNode context=(ObjectNode)shape(candidate).at("/properties/context");
        context.put("maxProperties",2);
        ObjectNode map=context.withObject("/properties").putObject("events/~");
        map.put("type","object").put("maxProperties",3);
        map.putObject("properties"); map.putArray("required");
        map.putObject("additionalProperties").put("type","string").put("format","date").put("maxLength",10);
        addDescriptor(candidate,"#/properties/context/properties/events~1~0","Datas de eventos","Mapa sintético de datas civis de eventos.");
        addDescriptor(candidate,"#/properties/context/properties/events~1~0/additionalProperties","Data do evento","Data civil de cada entrada do mapa sintético.");
        ObjectNode value=values().deepCopy();
        value.withObject("/context").putObject("events/~").put("first","2024-02-29");
        assertThat(errors(candidate)).isEmpty();
        assertThat(snapshotErrors(candidate,value)).isEmpty();
        value.withObject("/context").withObject("/events~1~0").put("first","2024-02-30");
        assertThat(snapshotErrors(candidate,value)).isNotEmpty();
    }

    @Test
    void timestampsKeepOffsetAndAreNotCivilDates() throws Exception {
        ObjectNode candidate=catalog().deepCopy();
        ObjectNode context=(ObjectNode)shape(candidate).at("/properties/context");
        context.put("maxProperties",2);
        context.withObject("/properties").putObject("capturedAt").put("type","string").put("format","date-time").put("maxLength",40);
        addDescriptor(candidate,"#/properties/context/properties/capturedAt","Instante de captura","Instante sintético com offset explícito, sem truncamento para data civil.");
        assertThat(errors(candidate)).isEmpty();
        ObjectNode value=values().deepCopy();
        for(String valid:List.of("2024-02-29T15:30:00Z","2024-02-29T12:30:00-03:00")) {
            value.withObject("/context").put("capturedAt",valid);
            assertThat(snapshotErrors(candidate,value)).as(valid).isEmpty();
        }
        for(String invalid:List.of("2024-02-29","2024-02-29T15:30:00","2024-02-30T15:30:00Z")) {
            value.withObject("/context").put("capturedAt",invalid);
            assertThat(snapshotErrors(candidate,value)).as(invalid).isNotEmpty();
        }
    }

    private static void addDescriptor(JsonNode candidate,String pointer,String label,String description) {
        ObjectNode descriptor=fact(candidate).deepCopy().put("schemaPointer",pointer);
        descriptor.putObject("labels").put("pt-BR",label);
        descriptor.putObject("descriptions").put("pt-BR",description);
        ((ArrayNode)op(candidate).get("facts")).add(descriptor);
    }

    @Test
    void determinationGoldenUsesBothBindingsAndKeepsContextualInput() throws Exception {
        JsonNode graph=MAPPER.readTree(CONTRACTS.resolve("examples/determination-v02.candidate.json").toFile());
        JsonNode schema=MAPPER.readTree(Path.of("src/main/resources/domain-catalog/contracts/praxis-domain-catalog-v0.2.schema.json").toFile());
        for(String kind:List.of("node","binding","edge")) {
            ObjectNode wrapper=MAPPER.createObjectNode().put("$ref","#/$defs/"+kind);
            wrapper.set("$defs",schema.get("$defs"));
            for(JsonNode item:graph.get(kind+"s"))assertThat(FACTORY.getSchema(wrapper).validate(item)).as(kind).isEmpty();
        }
        JsonNode compiled=graph.at("/nodes/5/metadata/reactiveDetermination");
        JsonNode api=graph.at("/bindings/0");
        JsonNode form=graph.at("/bindings/1/target");
        assertThat(api.path("bindingType").asText()).isEqualTo("api_operation");
        assertThat(api.path("target")).isEqualTo(compiled.path("capability"));
        assertThat(graph.at("/bindings/1/bindingType").asText()).isEqualTo("form_config");
        assertThat(form.path("configuration")).isEqualTo(compiled);
        assertThat(form.path("schemaOperationId")).isEqualTo(compiled.at("/scope/schemaOperationId"));
        assertThat(form.path("formMode")).isEqualTo(compiled.at("/scope/formMode"));
        assertThat(form.path("determinationId")).isEqualTo(compiled.path("id"));
        assertThat(form.path("configurationPath").asText()).isEqualTo("/x-ui/reactiveDeterminations");
        assertThat(compiled.path("inputs")).anyMatch(input->input.path("fieldPath").asText().equals("/id")
                &&input.path("requestPath").asText().equals("/periodId"));
        assertThat(compiled.at("/trigger/sourcePaths")).noneMatch(input->input.asText().equals("/id"));
        assertThat(graph.path("edges")).noneMatch(edge->edge.has("metadata"));
        assertThat(graph.path("edges")).filteredOn(edge->edge.path("edgeType").asText().equals("depends_on"))
                .allMatch(edge->edge.path("sourceNodeKey").asText().equals(graph.at("/nodes/5/nodeKey").asText()));
    }

    @Test
    void graphV02AcceptsNodeExtensionButRejectsEdgeMetadata() throws Exception {
        ObjectNode schema=(ObjectNode)MAPPER.readTree(Path.of("src/main/resources/domain-catalog/contracts/praxis-domain-catalog-v0.2.schema.json").toFile());
        ObjectNode edgeSchema=MAPPER.createObjectNode();
        edgeSchema.set("$defs",schema.get("$defs"));
        edgeSchema.put("$ref","#/$defs/edge");
        ObjectNode edge=MAPPER.createObjectNode().put("edgeKey","edge:test").put("sourceNodeKey","operation:test")
                .put("targetNodeKey","field:test").put("edgeType","depends_on");
        assertThat(FACTORY.getSchema(edgeSchema).validate(edge)).isEmpty();
        edge.putObject("metadata").put("dependencyRole","trigger");
        assertThat(FACTORY.getSchema(edgeSchema).validate(edge)).isNotEmpty();
        ObjectNode nodeSchema=edgeSchema.deepCopy().put("$ref","#/$defs/node");
        ObjectNode node=MAPPER.createObjectNode().put("nodeKey","operation:test").put("contextKey","context:test")
                .put("nodeType","operation").put("label","Determinação").put("status","active");
        node.putObject("metadata").put("operationKind","reactive_determination");
        assertThat(FACTORY.getSchema(nodeSchema).validate(node)).isEmpty();
    }

    private record Mutation(String name, Consumer<ObjectNode> change) {}
    private static Mutation mutation(String name, Consumer<ObjectNode> change) { return new Mutation(name,change); }
    private static ObjectNode op(JsonNode n) { return (ObjectNode)n.at("/operations/0"); }
    private static ObjectNode shape(JsonNode n) { return (ObjectNode)n.at("/operations/0/snapshotSchema"); }
    private static ObjectNode fact(JsonNode n) { return (ObjectNode)n.at("/operations/0/facts/0"); }
    private static ObjectNode array(JsonNode n) { return (ObjectNode)n.at("/operations/0/snapshotSchema/properties/neighborhood/properties/periods"); }
    private static JsonNode catalog() throws Exception { return MAPPER.readTree(CONTRACTS.resolve("examples/ontology-facts-v2.candidate.json").toFile()); }
    private static JsonNode values() throws Exception { return MAPPER.readTree(CONTRACTS.resolve("examples/ontology-facts-v2.values.json").toFile()); }

    // Documentary checker for G1; runtime ownership remains Engine/Config as described in the ADR.
    static List<String> errors(JsonNode catalog) throws Exception {
        List<String> errors=new ArrayList<>();
        for (JsonNode operation : catalog.path("operations")) {
            errors.addAll(structuralLimitErrors(operation.path("snapshotSchema")));
        }
        if (!errors.isEmpty()) return errors;
        JsonNode meta=MAPPER.readTree(CONTRACTS.resolve("praxis-domain-rule-fact-catalog-v2.candidate.schema.json").toFile());
        FACTORY.getSchema(meta).validate(catalog).forEach(e->errors.add(e.getMessage()));
        if(!errors.isEmpty())return errors;
        if(MAPPER.writeValueAsBytes(catalog).length>1_024_000)errors.add("catalog bytes");
        Set<String> operations=new HashSet<>();
        for(JsonNode operation:catalog.get("operations")) {
            if(!operations.add(operation.path("operationKey").asText()))errors.add("duplicate operation");
            Set<String> pointers=new HashSet<>();
            collectShapes(operation.get("snapshotSchema"),"#",pointers,errors,0,new int[]{0});
            pointers.remove("#");
            Set<String> described=new HashSet<>();
            for(JsonNode descriptor:operation.get("facts")) {
                String pointer=descriptor.path("schemaPointer").asText();
                if(!pointers.contains(pointer))errors.add("unknown schema pointer "+pointer);
                if(!described.add(pointer))errors.add("duplicate descriptor");
            }
            if(!described.equals(pointers))errors.add("incomplete semantic coverage");
            // A whole-container projection cannot bypass a protected descendant.
            // MASK and HASH are intentionally not assigned an invented numeric rank.
            for(JsonNode parent:operation.get("facts")) for(JsonNode child:operation.get("facts")) {
                if(!child.path("schemaPointer").asText().startsWith(parent.path("schemaPointer").asText()+"/"))continue;
                String p=parent.path("redaction").asText(), c=child.path("redaction").asText();
                if((p.equals("NONE")&&!c.equals("NONE")) || (c.equals("OMIT")&&!p.equals("OMIT")))
                    errors.add("container protection not demonstrated");
            }
        }
        return errors;
    }

    private static void collectShapes(JsonNode node,String pointer,Set<String> pointers,List<String> errors,int depth,int[] count) {
        pointers.add(pointer);
        if(depth>32||++count[0]>4096) { errors.add("schema limits"); return; }
        String type=node.path("type").isArray()?node.path("type").get(0).asText():node.path("type").asText();
        if(node.has("enum")) {
            boolean nullable=node.path("type").isArray()&&node.path("type").toString().contains("\"null\"");
            node.get("enum").forEach(v->{
                if(v.isNull()&&!nullable)errors.add("enum null outside type");
                if(v.isTextual()&&v.textValue().codePointCount(0,v.textValue().length())>node.path("maxLength").asInt())errors.add("enum exceeds string bound");
            });
        }
        if(type.equals("object")) {
            Set<String> required=new HashSet<>();
            node.path("required").forEach(v->required.add(v.asText()));
            required.forEach(k->{if(!node.path("properties").has(k))errors.add("unknown required");});
            if(node.path("maxProperties").asInt()<required.size())errors.add("impossible object cardinality");
            node.path("properties").fields().forEachRemaining(entry->collectShapes(entry.getValue(),
                    pointer+"/properties/"+entry.getKey().replace("~","~0").replace("/","~1"),pointers,errors,depth+1,count));
            if(node.path("additionalProperties").isObject())collectShapes(node.get("additionalProperties"),pointer+"/additionalProperties",pointers,errors,depth+1,count);
        } else if(type.equals("array")) {
            if(node.path("minItems").asInt()>node.path("maxItems").asInt())errors.add("inverted cardinality");
            collectShapes(node.get("items"),pointer+"/items",pointers,errors,depth+1,count);
        } else if((type.equals("number")||type.equals("integer"))&&node.has("minimum")&&node.has("maximum")
                &&node.get("minimum").decimalValue().compareTo(node.get("maximum").decimalValue())>0)errors.add("inverted numeric bounds");
    }

    static List<String> snapshotErrors(JsonNode catalog,JsonNode value) {
        List<String> errors=new ArrayList<>();
        try {
            new org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine().validateResultValue(
                    value, org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits.DEFAULT);
        } catch (RuntimeException invalid) {
            errors.add("engine fact limits: " + invalid.getMessage());
            return errors;
        }
        JsonNode schema=shape(catalog);
        FACTORY.getSchema(schema).validate(value).forEach(e->errors.add(e.getMessage()));
        checkDates(schema,value,errors);
        return errors;
    }

    private static void checkDates(JsonNode schema,JsonNode value,List<String> errors) {
        if(value==null||value.isNull())return;
        if("date".equals(schema.path("format").asText())&&value.isTextual()) {
            try {
                if(!value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new DateTimeParseException("date",value.asText(),0);
                LocalDate.parse(value.asText());
            } catch(DateTimeParseException invalid) { errors.add("invalid civil date"); }
        }
        if("date-time".equals(schema.path("format").asText())&&value.isTextual()) {
            try {
                if(!value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})"))throw new DateTimeParseException("timestamp",value.asText(),0);
                OffsetDateTime.parse(value.asText());
            } catch(DateTimeParseException invalid) { errors.add("invalid timestamp"); }
        }
        if(value.isTextual()) {
            try {
                switch(schema.path("format").asText()) {
                    case "praxis-local-date-time" -> {
                        // Civil coordinate with seconds, no offset or invented time zone.
                        if (!value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?"))
                            throw new IllegalArgumentException("local date-time grammar");
                        java.time.LocalDateTime.parse(value.asText());
                    }
                    case "praxis-local-time" -> {
                        // LocalTime.toString may omit seconds; offset/zoned time is a different type.
                        if(!value.asText().matches("[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?"))
                            throw new IllegalArgumentException("local time grammar");
                        java.time.LocalTime.parse(value.asText());
                    }
                    case "praxis-duration" -> {
                        // Java Duration: time-based ISO amount, no years/calendar months.
                        // Sign is structural; the schedule decision rejects negative travel time.
                        java.time.Duration.parse(value.asText());
                    }
                    case "praxis-zone-id" -> {
                        // Freeze tzdb version with the consuming runtime coordinate for replay.
                        java.time.ZoneId.of(value.asText());
                    }
                    case "praxis-decimal" -> {
                        // Candidate neutral wire format: one exact value, preserving decimal scale.
                        // Canonical BigDecimal text never expands a large exponent to plain digits.
                        String decimal = value.textValue();
                        if (decimal.length() > 128 || !new java.math.BigDecimal(decimal).toString().equals(decimal))
                            throw new IllegalArgumentException("noncanonical decimal");
                    }
                    default -> { }
                }
            } catch (java.time.DateTimeException | IllegalArgumentException invalid) {
                errors.add("invalid " + schema.path("format").asText());
            }
        }
        if(value.isObject())value.fields().forEachRemaining(e->{
            JsonNode property=schema.path("properties").get(e.getKey());
            if(property==null&&schema.path("additionalProperties").isObject())property=schema.get("additionalProperties");
            if(property!=null)checkDates(property,e.getValue(),errors);
        });
        if(value.isArray()&&schema.has("items"))value.forEach(item->checkDates(schema.get("items"),item,errors));
    }

    static List<String> structuralLimitErrors(JsonNode root) {
        record Item(JsonNode schema, int depth) { }
        var pending = new java.util.ArrayDeque<Item>();
        pending.add(new Item(root, 0));
        int count = 0;
        while (!pending.isEmpty()) {
            Item item = pending.removeLast();
            if (item.depth() > 32 || ++count > 4096) return List.of("schema limits");
            item.schema().path("properties").forEach(child -> pending.add(new Item(child, item.depth()+1)));
            if (item.schema().has("items")) pending.add(new Item(item.schema().get("items"), item.depth()+1));
            if (item.schema().path("additionalProperties").isObject())
                pending.add(new Item(item.schema().get("additionalProperties"), item.depth()+1));
        }
        return List.of();
    }
}
