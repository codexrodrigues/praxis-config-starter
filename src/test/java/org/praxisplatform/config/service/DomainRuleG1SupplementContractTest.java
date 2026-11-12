package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

@Tag("unit")
class DomainRuleG1SupplementContractTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @TestFactory Stream<DynamicTest> realCompilerMetadataHasBothBindingsWithoutInventedCausality() throws Exception {
        String root=System.getProperty("ontology.g1.host-contracts");assumeTrue(root!=null);
        JsonNode graphs=JSON.readTree(Path.of(root,"completion","determination-graphs.candidate.json").toFile());
        JsonNode compiled=JSON.readTree(Path.of(root,"completion","determination-projections.json").toFile());
        assertThat(graphs).hasSize(46);assertThat(compiled).hasSize(46);
        JsonNode schema=JSON.readTree(Path.of("src/main/resources/domain-catalog/contracts/praxis-domain-catalog-v0.2.schema.json").toFile());
        var factory=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012);
        var tests=new ArrayList<DynamicTest>();
        for(int n=0;n<graphs.size();n++) {
            JsonNode graph=graphs.get(n), expected=compiled.get(n).path("metadata");
            tests.add(DynamicTest.dynamicTest("compiled determination "+graph.path("schemaOperationId").asText(),()->{
                for(String kind:List.of("node","edge","binding")) {
                    ObjectNode wrapper=JSON.createObjectNode().put("$ref","#/$defs/"+kind);wrapper.set("$defs",schema.path("$defs"));
                    for(JsonNode item:graph.path(kind+"s"))assertThat(factory.getSchema(wrapper).validate(item)).as(kind).isEmpty();
                }
                String operation=graph.at("/nodes/0/nodeKey").asText();
                assertThat(graph.at("/nodes/0/metadata/reactiveDetermination")).isEqualTo(expected);
                assertThat(graph.at("/bindings/0/target")).isEqualTo(expected.path("capability"));
                assertThat(graph.at("/bindings/1/target/configuration")).isEqualTo(expected);
                assertThat(graph.path("edges")).hasSize(expected.path("inputs").size()+expected.path("outputs").size());
                for(JsonNode edge:graph.path("edges")) {
                    assertThat(edge.has("metadata")).isFalse();
                    assertThat(edge.path(edge.path("edgeType").asText().equals("depends_on")?"sourceNodeKey":"targetNodeKey").asText()).isEqualTo(operation);
                }
                if(expected.path("id").asText().contains("venda")) {
                    assertThat(expected.path("inputs")).anyMatch(i->i.path("requestPath").asText().equals("/vendaId"));
                    assertThat(expected.at("/trigger/sourcePaths")).noneMatch(p->p.asText().equals("/id"));
                }
            }));
        }
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> scopedCustomDefinitionsAreClosedTypedAndPinned() throws Exception {
        String root=System.getProperty("ontology.g1.host-contracts");
        assumeTrue(root!=null,"Cross-checkout candidate requires ontology.g1.host-contracts");
        JsonNode contract=JSON.readTree(Path.of(root,"completion","custom-fields.candidate.json").toFile());
        assertThat(contract.path("adapters")).hasSize(141);
        var tests=new ArrayList<DynamicTest>();
        for(JsonNode scope:contract.path("scopes")) tests.add(DynamicTest.dynamicTest("custom fields orgao "+scope.path("tenantId").asText(),()->{
            JsonNode catalog=scope.path("catalog");ObjectNode value=scope.path("positive").deepCopy();
            assertThat(DomainRuleFactCatalogCandidateContractTest.errors(catalog)).isEmpty();
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,value)).isEmpty();
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(scope.path("definitionSnapshot"))));
            assertThat(digest).isEqualTo(scope.path("definitionSnapshotHash").asText());
            for(String kind:List.of("unknown","missing","required-null","type","enum","date","tenant","hash")) {
                ObjectNode invalid=value.deepCopy();ObjectNode fields=invalid.withObject("/draft/customFields");
                switch(kind) {
                    case "unknown" -> fields.put("unregistered",1);
                    case "missing" -> fields.remove("processo_sei");
                    case "required-null" -> fields.putNull("processo_sei");
                    case "type" -> {if(scope.path("tenantId").asText().equals("2"))fields.put("numero_calcado","40");else fields.put("numero_calcado",40);}
                    case "enum" -> fields.put("modalidade_entrega","INVENTADA");
                    case "date" -> fields.put("data_entrega","2026-02-30");
                    case "tenant" -> invalid.withObject("/context").put("empCodigo",7);
                    case "hash" -> invalid.withObject("/context").put("definitionSnapshotHash","changed");
                }
                assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,invalid)).as(kind).isNotEmpty();
            }
            ObjectNode optional=value.deepCopy();optional.withObject("/draft/customFields").remove("numero_calcado");
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,optional)).isEmpty();
            optional.withObject("/draft/customFields").putNull("numero_calcado");
            assertThat(DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,optional)).isEmpty();
            ObjectNode unsafe=catalog.deepCopy();
            ((ObjectNode)unsafe.at("/operations/0/facts/0")).put("sensitivity","NON_SENSITIVE").put("redaction","NONE");
            assertThat(DomainRuleFactCatalogCandidateContractTest.errors(unsafe)).contains("container protection not demonstrated");
        }));
        return tests.stream();
    }
}
