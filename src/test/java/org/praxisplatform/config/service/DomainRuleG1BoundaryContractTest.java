package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;

/** Candidate admission boundaries; uses the real Engine for native fact limits. */
@Tag("unit")
class DomainRuleG1BoundaryContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PraxisJsonLogicEngine ENGINE = new PraxisJsonLogicEngine();

    @TestFactory
    Stream<DynamicTest> temporalTypesKeepTheirOwnGrammar() {
        record Format(String name, List<String> valid, List<String> invalid) { }
        return Stream.of(
                new Format("date", List.of("2024-02-29"), List.of("2023-02-29", "2024-01-01T00:00:00Z")),
                new Format("date-time", List.of("2024-02-29T12:00:00-03:00"), List.of("2024-02-30T12:00:00Z", "2024-01-01T12:00:00")),
                new Format("praxis-local-time", List.of("00:00", "23:59:59.123456789"), List.of("24:00", "12:00Z", "12:00:60")),
                new Format("praxis-duration", List.of("PT0S", "PT30M", "P1DT2H", "PT-1S"), List.of("P1M", "P1Y", "30 minutes", "PT")),
                new Format("praxis-zone-id", List.of("America/Sao_Paulo", "UTC", "+03:00"), List.of("America/Invented", "PST"))
        ).map(format -> DynamicTest.dynamicTest(format.name(), () -> {
            ObjectNode shape = object();
            shape.withObject("/properties").putObject("value").put("type", "string").put("maxLength",128).put("format",format.name());
            shape.withArray("/required").add("value"); shape.put("maxProperties",1);
            ObjectNode catalog = catalog(shape);
            assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog)).isEmpty();
            for(String valid : format.valid()) assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,
                    JSON.createObjectNode().put("value",valid))).as(valid).isEmpty();
            for(String invalid : format.invalid()) assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,
                    JSON.createObjectNode().put("value",invalid))).as(invalid).isNotEmpty();
        }));
    }

    @Test
    void schemaDepthIsCheckedBeforeRecursiveMetaValidation() throws Exception {
        ObjectNode root=object(), tail=root;
        for(int i=0;i<32;i++) {
            ObjectNode next=object();tail.withObject("/properties").set("child",next);
            tail.withArray("/required").add("child");tail.put("maxProperties",1);tail=next;
        }
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog(root))).isEmpty();
        tail.withObject("/properties").set("child",object());tail.withArray("/required").add("child");tail.put("maxProperties",1);
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog(root))).contains("schema limits");
        // Test a hostile depth without traversing it in a schema library or generating descriptors.
        ObjectNode hostile=object();tail=hostile;
        for(int i=0;i<10_000;i++) {ObjectNode next=object();tail.withObject("/properties").set("x",next);tail=next;}
        assertThat(DomainRuleFactCatalogCandidateContractTest.structuralLimitErrors(hostile)).contains("schema limits");
    }

    @Test
    void schemaNodeBudgetAndDescriptorBudgetAreIndependent() throws Exception {
        ObjectNode root=object();
        for(int i=0;i<256;i++) root.withObject("/properties").set("p"+i, JSON.createObjectNode().put("type","integer"));
        root.put("maxProperties",256);
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog(root))).isEmpty();
        root.withObject("/properties").set("overflow",JSON.createObjectNode().put("type","integer"));
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog(root))).isNotEmpty();
        ObjectNode broad=object();
        // 1 root + 15 objects + 4080 scalar children = 4096 structural nodes.
        for(int i=0;i<15;i++) {
            ObjectNode child=object(); broad.withObject("/properties").set("p"+i,child);
            for(int n=0;n<272;n++) child.withObject("/properties").set("p"+n,JSON.createObjectNode().put("type","integer"));
        }
        assertThat(DomainRuleFactCatalogCandidateContractTest.structuralLimitErrors(broad)).isEmpty();
        broad.withObject("/properties").set("extra",JSON.createObjectNode().put("type","integer"));
        assertThat(DomainRuleFactCatalogCandidateContractTest.structuralLimitErrors(broad)).contains("schema limits");
        // The broad fixture intentionally exceeds the stricter 256-descriptor/object gates.
    }

    @Test
    void catalogOperationAndUtf8ByteBoundaries() throws Exception {
        ObjectNode shape=object();
        for(int i=0;i<16;i++)shape.withObject("/properties").set("p"+i,JSON.createObjectNode().put("type","integer"));
        shape.put("maxProperties",16);
        ObjectNode catalog=catalog(shape);ArrayNode operations=(ArrayNode)catalog.get("operations");
        for(int i=1;i<64;i++)operations.add(((ObjectNode)operations.get(0)).deepCopy().put("operationKey","operation-"+i));
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog)).isEmpty();
        ObjectNode tooMany=catalog.deepCopy();tooMany.withArray("/operations").add(((ObjectNode)operations.get(0)).deepCopy().put("operationKey","overflow"));
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(tooMany)).isNotEmpty();
        int remaining=1_024_000-JSON.writeValueAsBytes(catalog).length;
        ObjectNode last=null;
        outer:for(JsonNode operation:operations)for(JsonNode fact:operation.path("facts")) {
            ObjectNode description=(ObjectNode)fact.path("descriptions");last=description;
            int added=Math.min(3999,remaining);description.put("pt-BR","x".repeat(1+added));remaining-=added;
            if(remaining==0)break outer;
        }
        assertThat(remaining).isZero();assertThat(JSON.writeValueAsBytes(catalog)).hasSize(1_024_000);
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog)).isEmpty();
        // Replace one ASCII char by a two-byte char without exceeding descriptor character length.
        String value=last.path("pt-BR").asText();last.put("pt-BR","é"+value.substring(1));
        assertThat(JSON.writeValueAsBytes(catalog)).hasSize(1_024_001);
        assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog)).contains("catalog bytes");
    }

    @Test
    void engineFactDepthNodesStringsAndBytesAreEnforcedTogether() throws Exception {
        JsonNode value=JSON.getNodeFactory().numberNode(1);
        for(int i=0;i<64;i++)value=JSON.createArrayNode().add(value);
        JsonNode atDepth=value;
        assertThatCode(()->ENGINE.validateResultValue(atDepth,JsonLogicLimits.DEFAULT)).doesNotThrowAnyException();
        JsonNode tooDeep=JSON.createArrayNode().add(value);
        assertThatThrownBy(()->ENGINE.validateResultValue(tooDeep,JsonLogicLimits.DEFAULT)).isInstanceOf(RuntimeException.class);
        ArrayNode items=JSON.createArrayNode();for(int i=0;i<9999;i++)items.add(0);
        assertThatCode(()->ENGINE.validateResultValue(items,JsonLogicLimits.DEFAULT)).doesNotThrowAnyException();
        items.add(0);assertThatThrownBy(()->ENGINE.validateResultValue(items,JsonLogicLimits.DEFAULT)).isInstanceOf(RuntimeException.class);
        assertThatCode(()->ENGINE.validateResultValue("x".repeat(64_000),JsonLogicLimits.DEFAULT)).doesNotThrowAnyException();
        assertThatThrownBy(()->ENGINE.validateResultValue("x".repeat(64_001),JsonLogicLimits.DEFAULT)).isInstanceOf(RuntimeException.class);
        // Code points and UTF-16 units differ: 32001 supplementary characters exceed the Engine.
        assertThatThrownBy(()->ENGINE.validateResultValue("😀".repeat(32_001),JsonLogicLimits.DEFAULT)).isInstanceOf(RuntimeException.class);
        ArrayNode bytes=JSON.createArrayNode();for(int i=0;i<4;i++)bytes.add("x".repeat(63_996));
        int delta=256_000-JSON.writeValueAsBytes(bytes).length;
        bytes.set(0,JSON.getNodeFactory().textNode(bytes.get(0).asText()+"x".repeat(delta)));
        assertThat(JSON.writeValueAsBytes(bytes)).hasSize(256_000);
        assertThatCode(()->ENGINE.validateResultValue(bytes,JsonLogicLimits.DEFAULT)).doesNotThrowAnyException();
        bytes.set(1,JSON.getNodeFactory().textNode("é"+bytes.get(1).asText().substring(1)));
        assertThatThrownBy(()->ENGINE.validateResultValue(bytes,JsonLogicLimits.DEFAULT)).isInstanceOf(RuntimeException.class);
    }

    static ObjectNode object() {
        ObjectNode n=JSON.createObjectNode().put("type","object").put("additionalProperties",false).put("maxProperties",0);
        n.putObject("properties");n.putArray("required");return n;
    }
    static ObjectNode catalog(ObjectNode shape) {
        ObjectNode n=JSON.createObjectNode().put("schemaVersion","praxis.domain-rule-fact-catalog.v2");
        ObjectNode op=n.putArray("operations").addObject().put("operationKey","operation");op.set("snapshotSchema",shape);
        ArrayNode facts=op.putArray("facts");describe(shape,"#",facts);return n;
    }
    private static void describe(JsonNode shape,String pointer,ArrayNode facts) {
        shape.path("properties").fields().forEachRemaining(e->{
            String p=pointer+"/properties/"+e.getKey();ObjectNode fact=facts.addObject().put("schemaPointer",p).put("providerRef","g1:test").put("sensitivity","NON_SENSITIVE").put("redaction","NONE");
            fact.putObject("labels").put("pt-BR","x");fact.putObject("descriptions").put("pt-BR","x");fact.putArray("evidenceRefs").add("g1:boundary");
            describe(e.getValue(),p,facts);
        });
    }
}
