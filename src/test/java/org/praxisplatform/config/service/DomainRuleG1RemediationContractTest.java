package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationOptions;

/** G1 candidate-contract regressions. No Spring, JDBC, persistence or production validator. */
@Tag("unit")
class DomainRuleG1RemediationContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path directory() {
        String configured=System.getProperty("ontology.g1.host-contracts");
        assumeTrue(configured!=null,"Cross-checkout G1 gate requires ontology.g1.host-contracts");
        return Path.of(configured,"completion");
    }
    private static JsonNode read(String file)throws Exception{return JSON.readTree(directory().resolve(file).toFile());}
    private static JsonNode catalog(String name)throws Exception{return read("operational-"+name+".fact-catalog.candidate.json");}
    private static ObjectNode facts(String name)throws Exception{return read("operational-"+name+".fixtures.json").get(0).path("positive").deepCopy();}
    private static JsonNode operation(String key)throws Exception{
        for(JsonNode op:read("operational-bindings.json"))if(key.equals(op.path("enum").asText()))return op;
        throw new AssertionError(key);
    }
    private static Object evaluate(JsonNode expression,JsonNode facts){
        var roots=new ArrayList<String>();facts.fieldNames().forEachRemaining(roots::add);
        return new PraxisJsonLogicEngine().evaluate(expression,facts,
                new JsonLogicEvaluationOptions(roots,null,false,"2026-11-04T12:00:00Z","America/Sao_Paulo"));
    }
    private static List<String> errors(JsonNode catalog,JsonNode facts){
        return DomainRuleFactCatalogCandidateContractTest.snapshotErrors(catalog,facts);
    }
    private static JsonNode single(JsonNode operation){
        var result=JSON.createObjectNode().put("schemaVersion","praxis.domain-rule-fact-catalog.v2");
        result.putArray("operations").add(operation);return result;
    }

    @TestFactory Stream<DynamicTest> exactDecimalRetificationHasOneAuthority()throws Exception{
        record Case(String name,String before,String after,boolean allowed){}
        return List.of(new Case("changed paid amount","100","200",false),
                new Case("scale is cadastral data","100.0","100.00",false),
                new Case("same exact value","100.00","100.00",true),
                new Case("beyond binary precision","9007199254740992.01","9007199254740992.02",false),
                new Case("coherent null",null,null,true),new Case("null to amount",null,"1.00",false),
                new Case("amount to null","1.00",null,false),new Case("large exponent","1E+1000","2E+1000",false))
                .stream().map(c->DynamicTest.dynamicTest(c.name(),()->{
                    var facts=facts("venda-retificacao");facts.withObject("/current").put("situacao","PAGA").put("diasVendidos",10);
                    facts.withObject("/draft").put("diasVendidos",10);
                    facts.withObject("/current").put("valorIndenizacao",c.before());
                    facts.withObject("/draft").put("valorIndenizacao",c.after());
                    assertThat(errors(catalog("venda-retificacao"),facts)).isEmpty();
                    assertThat(evaluate(operation("VENDA_RETIFICACAO").path("expression"),facts)).isEqualTo(c.allowed());
                }));
    }

    @Test void decimalAdmissionRejectsParallelValuesAndNoncanonicalRepresentations()throws Exception{
        JsonNode catalog=catalog("venda-retificacao");
        for(String bad:List.of("NaN","Infinity","01.00","+1.00","1e2"," 1.00","-0.00","1,00","0".repeat(129))) {
            var value=facts("venda-retificacao");value.withObject("/draft").put("valorIndenizacao",bad);
            assertThat(errors(catalog,value)).as(bad).isNotEmpty();
        }
        for(String canonical:List.of("0","0.00","-1.00","1E+1000","1E-1000","123456789012345678901234567890.001")) {
            assertThat(new BigDecimal(canonical).toString()).isEqualTo(canonical);
            var value=facts("venda-retificacao");value.withObject("/draft").put("valorIndenizacao",canonical);
            assertThat(errors(catalog,value)).as(canonical).isEmpty();
        }
        var missing=facts("venda-retificacao");missing.withObject("/draft").remove("valorIndenizacao");
        assertThat(errors(catalog,missing)).isNotEmpty();
        var old=facts("venda-retificacao");old.withObject("/draft").put("valorIndenizacao",200).putNull("valorIndenizacaoRepresentation");
        assertThat(errors(catalog,old)).isNotEmpty();
        assertThat(operation("VENDA_RETIFICACAO").toString()).doesNotContain("valorIndenizacaoRepresentation");
    }

    @TestFactory Stream<DynamicTest> previewUsesRawLedgerAndAddsOnlyThePriorConsumedDebit()throws Exception{
        record Case(String name,String priorState,Integer priorDebit,int raw,int requested,boolean allowed){}
        return List.of(new Case("create has no prior debit",null,null,5,6,false),
                new Case("same period exact availability","AUTORIZADA",10,5,15,true),
                new Case("double add-back regression","AUTORIZADA",10,5,20,false),
                new Case("cancelled debit already released","CANCELADO",10,5,6,false),
                new Case("reversed debit already released","ESTORNADA",10,5,6,false),
                new Case("cancelled uses raw balance","CANCELADO",10,5,5,true))
                .stream().map(c->DynamicTest.dynamicTest(c.name(),()->{
                    JsonNode op=operation("PREVIA_VENDA_LIMITE");
                    JsonNode binding=op.at("/calls/0/argumentBindings/1");
                    assertThat(binding.path("legacySourceExpression").asText()).isEqualTo("available");
                    assertThat(binding.path("candidateSourceExpression").asText()).isEqualTo("pa.getSaldoDias()");
                    var value=facts("previa-venda-limite");
                    // Model the declared Java source, independently of the old adjusted 'available'.
                    record Period(int saldoDias) { int getSaldoDias(){return saldoDias;} }
                    var pa=new Period(c.raw());
                    value.withObject("/periodoAquisitivo").put("saldoDias",pa.getSaldoDias());
                    value.withObject("/draft").put("diasVendidos",c.requested());
                    if(c.priorState()==null)value.putNull("current");
                    else value.putObject("current").put("situacao",c.priorState()).put("diasVendidos",c.priorDebit());
                    assertThat(errors(catalog("previa-venda-limite"),value)).isEmpty();
                    assertThat(evaluate(op.path("expression"),value)).isEqualTo(c.allowed());
                }));
    }

    @Test void previewRetainsPeriodOriginAndUnknownBalanceGuards()throws Exception{
        var relation=facts("licenca-periodo-edicao");
        relation.withObject("/current").put("periodoAquisitivoLicencaId",101);
        relation.withObject("/draft").put("periodoAquisitivoLicencaId",102);
        assertThat(evaluate(operation("LICENCA_PERIODO_EDICAO").path("expression"),relation)).isEqualTo(false);
        var linked=facts("venda-abono-origem");linked.withObject("/current").put("licencaEspecialGozoId",9);
        linked.withObject("/context").put("action","previa");
        assertThat(evaluate(operation("VENDA_ABONO_ORIGEM").path("expression"),linked)).isEqualTo(false);
        var unknown=facts("previa-venda-saldo-apurado");unknown.withObject("/periodoAquisitivo").putNull("saldoDias");
        assertThat(evaluate(operation("PREVIA_VENDA_SALDO_APURADO").path("expression"),unknown)).isEqualTo(false);
        assertThat(operation("PREVIA_VENDA_LIMITE").path("contextualInputs").get(0).path("scope").asText())
                .isEqualTo("AUTHENTICATED_TENANT_AND_SAME_PERIODO");
    }

    @Test void persistedConsumptionNullAndMissingAreRejectedBeforeArithmetic()throws Exception{
        var catalog=catalog("periodo-saldo-contabil");var complete=facts("periodo-saldo-contabil");
        assertThat(errors(catalog,complete)).isEmpty();
        assertThat(evaluate(operation("PERIODO_SALDO_CONTABIL").path("expression"),complete)).isEqualTo(true);
        for(String name:List.of("diasGozo","diasAbono","id","empCodigo"))for(boolean missing:List.of(false,true)) {
            var invalid=complete.deepCopy();var item=(ObjectNode)invalid.at("/neighborhood/gozosLicenca/0");
            if(missing)item.remove(name);else item.putNull(name);
            assertThat(errors(catalog,invalid)).as(name+" missing="+missing).isNotEmpty();
        }
        var empty=complete.deepCopy();empty.withObject("/neighborhood").putArray("gozosLicenca");
        assertThat(errors(catalog,empty)).isEmpty();
        var absent=complete.deepCopy();absent.withObject("/neighborhood").remove("gozosLicenca");
        assertThat(errors(catalog,absent)).isNotEmpty();
    }

    @TestFactory Stream<DynamicTest> persistedOriginsHaveEffectiveNonNullAdmission()throws Exception{
        var tests=new ArrayList<DynamicTest>();var groups=new java.util.LinkedHashMap<String,List<JsonNode>>();
        for(JsonNode row:read("remediation/nullability.json"))groups.computeIfAbsent(row.path("catalog").asText()+"|"+row.path("operationKey").asText(),x->new ArrayList<>()).add(row);
        for(var entry:groups.entrySet())tests.add(DynamicTest.dynamicTest(entry.getKey(),()->{
            JsonNode first=entry.getValue().getFirst(),catalog=read(first.path("catalog").asText()),op=null;
            for(JsonNode item:catalog.path("operations"))if(item.path("operationKey").equals(first.path("operationKey")))op=item;
            assertThat(op).isNotNull();JsonNode scope=single(op);
            ObjectNode complete=(ObjectNode)DomainRuleG1CompletionContractTest.witness(op.path("snapshotSchema"));
            assertThat(errors(scope,complete)).isEmpty();
            for(JsonNode row:entry.getValue()) {
                String pointer=row.path("pointer").asText().substring(1).replace("/properties/","/").replace("/items","/0");
                int split=pointer.lastIndexOf('/');ObjectNode invalid=complete.deepCopy();
                ((ObjectNode)invalid.at(pointer.substring(0,split))).putNull(pointer.substring(split+1));
                assertThat(errors(scope,invalid)).as(row.toString()).isNotEmpty();
            }
        }));
        return tests.stream();
    }

    @Test void unrelatedTenantPoliciesDoNotConsumeTheOccurrenceSnapshotBudget()throws Exception{
        JsonNode op=read("frequencias.fact-catalog.candidate.json").path("operations").get(1);
        JsonNode item=op.at("/snapshotSchema/properties/neighborhood/properties/regrasEstatutarias/items");
        var all=JSON.createArrayNode();
        for(int i=0;i<5000;i++) {
            ObjectNode row=(ObjectNode)DomainRuleG1CompletionContractTest.witness(item);
            row.put("id",i+1).put("empCodigo",2).put("tipofreq","F").put("codfreq",i<40?51:900+i);
            row.put("ativa",i%2==0);row.put("dtini",i%2==0?"2099-01-01":"1990-01-01");
            if(i==0)row.putNull("empCodigo");all.add(row);
        }
        ObjectNode foreign=all.get(1).deepCopy();foreign.put("empCodigo",7);all.add(foreign);
        ObjectNode value=(ObjectNode)DomainRuleG1CompletionContractTest.witness(op.path("snapshotSchema"));
        value.withObject("/context").put("empCodigo",2);value.withObject("/draft").put("tipofreq","F").put("codfreq",51);
        JsonNode recipe=null;
        for(JsonNode c:read("frequencias.operation-bindings.candidate.json").at("/profiles/1/collectionBindings"))if(c.path("collection").asText().equals("regrasEstatutarias"))recipe=c;
        assertThat(recipe.path("sourceFields")).isEqualTo(JSON.readTree("[\"tipofreq\",\"codfreq\"]"));
        ArrayNode selected=value.withObject("/neighborhood").putArray("regrasEstatutarias");
        for(JsonNode row:all) {
            boolean match=row.path("empCodigo").isNull()||row.path("empCodigo").asInt()==2;
            for(int i=0;i<recipe.path("sourceFields").size();i++)
                match&=value.path("draft").path(recipe.path("sourceFields").get(i).asText()).equals(row.path(recipe.path("targetFields").get(i).asText()));
            if(match)selected.add(row);
        }
        assertThat(selected).hasSize(40);assertThat(selected).anyMatch(x->!x.path("ativa").asBoolean());
        assertThat(selected).anyMatch(x->x.path("dtini").asText().equals("2099-01-01"));
        assertThat(selected).anyMatch(x->x.path("empCodigo").isNull());
        // Budget is shared with the other neighborhoods and the remainder of the snapshot.
        for(String name:List.of("frequencias","afastamentos")) {
            JsonNode shape=op.at("/snapshotSchema/properties/neighborhood/properties/"+name+"/items");
            ArrayNode rows=value.withObject("/neighborhood").putArray(name);
            for(int i=0;i<100;i++)rows.add(DomainRuleG1CompletionContractTest.witness(shape));
        }
        assertThat(errors(single(op),value)).isEmpty();
        // A concentrated workload outside the declared envelope must fail as a whole, never truncate.
        while(selected.size()<400)selected.add(selected.get(1).deepCopy());
        assertThat(errors(single(op),value)).anyMatch(x->x.startsWith("engine fact limits:"));
        assertThat(selected).hasSize(400);
    }

    @Test void actionSchemasAndBindingStagesDoNotAcceptTechnicalMethodNames()throws Exception{
        JsonNode catalog=read("periodos-aquisitivos-licenca-especial.fact-catalog.candidate.json");
        var value=read("periodos-aquisitivos-licenca-especial.fixtures.json").get(1).path("positive").deepCopy();
        for(String action:List.of("actionConverterEmDobro","invented","create")) {
            ((ObjectNode)value).withObject("/context").put("action",action);
            assertThat(errors(single(catalog.path("operations").get(1)),value)).isNotEmpty();
        }
        ((ObjectNode)value).withObject("/context").put("action","converter-em-dobro");
        assertThat(errors(single(catalog.path("operations").get(1)),value)).isEmpty();
        JsonNode binding=null;
        for(JsonNode row:read("periodos-aquisitivos-licenca-especial.operation-bindings.candidate.json").path("operations"))if(row.path("methodName").asText().equals("actionConverterEmDobro"))binding=row;
        assertThat(binding.at("/evaluationStages/0/contextAction").asText()).isEqualTo("converter-em-dobro");
        assertThat(binding.at("/evaluationStages/1/contextAction").isNull()).isTrue();
        assertThat(binding.path("contextAdmission").asText()).contains("DIFFERENT_KNOWN_ACTION");
        // Resolve the shared profile against server-owned invocation/stage constants.
        // This is an admission contract probe, not a second production validator.
        for(JsonNode stage:binding.path("evaluationStages")) {
            ObjectNode resolved=catalog.path("operations").get(1).deepCopy();
            var constants=stage.path("constantFacts").fields();
            while(constants.hasNext()) {
                var constant=constants.next();String[] segments=constant.getKey().substring(1).split("/");
                JsonNode shape=resolved.path("snapshotSchema");
                for(String segment:segments)shape=shape.path("properties").path(segment);
                if(shape.path("type").isTextual()&&shape.path("type").asText().equals("boolean"))continue;
                ((ObjectNode)shape).putArray("enum").add(constant.getValue());
            }
            ((ObjectNode)value).withObject("/context").set("action",stage.path("constantFacts").path("/context/action"));
            assertThat(errors(single(resolved),value)).isEmpty();
            ((ObjectNode)value).withObject("/context").put("action","reverter-conversao-dobro");
            assertThat(errors(single(resolved),value)).as("A valid action belonging to a different invocation must fail").isNotEmpty();
        }
    }

    @Test void allRealProfilesHaveAJointCapacityReferenceWithAnExactRejectionBoundary()throws Exception{
        var files=new ArrayList<Path>();
        for(Path dir:List.of(directory(),directory().getParent()))try(var list=java.nio.file.Files.list(dir)) {
            list.filter(p->p.getFileName().toString().endsWith(".fact-catalog.candidate.json")).sorted().forEach(files::add);
        }
        var evidence=JSON.createArrayNode();
        for(Path file:files)for(JsonNode op:JSON.readTree(file.toFile()).path("operations")) {
            ObjectNode base=(ObjectNode)DomainRuleG1CompletionContractTest.witness(op.path("snapshotSchema"));
            var arrays=new java.util.LinkedHashMap<String,JsonNode>();
            base.path("neighborhood").fields().forEachRemaining(e->{if(e.getValue().isArray()&&!e.getValue().isEmpty())arrays.put(e.getKey(),e.getValue().get(0).deepCopy());});
            assertThat(withinEngineBudget(base)).as(op.path("operationKey").asText()).isTrue();
            int low=arrays.isEmpty()?0:1, high=10001;
            while(!arrays.isEmpty()&&low+1<high) {
                int middle=(low+high)/2;
                if(withinEngineBudget(expand(base,arrays,middle)))low=middle;else high=middle;
            }
            ObjectNode accepted=arrays.isEmpty()?base:expand(base,arrays,low);
            assertThat(errors(single(op),accepted)).as(op.path("operationKey").asText()).isEmpty();
            if(!arrays.isEmpty())assertThat(withinEngineBudget(expand(base,arrays,high))).isFalse();
            var row=evidence.addObject().put("operationKey",op.path("operationKey").asText()).put("catalog",file.getFileName().toString());
            row.put("simultaneousCollections",arrays.size()).put("equalReferenceRowsPerCollection",low)
                    .put("firstRejectedRowsPerCollection",arrays.isEmpty()?0:high)
                    .put("acceptedBytes",JSON.writeValueAsBytes(accepted).length)
                    .put("scope","Fixed fully populated scalar witnesses; not a business SLA. Longer strings/custom fields share and reduce this budget.");
        }
        assertThat(evidence).hasSize(144);
        java.nio.file.Files.writeString(Path.of("target/ontology-g1-capacity-reference.json"),evidence.toPrettyString());
    }

    @Test void tenantCustomFieldsAndAllNeighborhoodsConsumeOneJointBudget()throws Exception{
        var evidence=JSON.createArrayNode();
        for(JsonNode scope:read("custom-fields.candidate.json").path("scopes")) {
            ObjectNode op=read("vinculos.fact-catalog.candidate.json").path("operations").get(1).deepCopy();
            JsonNode extension=scope.at("/catalog/operations/0");
            for(String root:List.of("draft","current","context")) {
                String field=root.equals("context")?"definitionSnapshotHash":"customFields";
                ObjectNode shape=(ObjectNode)op.at("/snapshotSchema/properties/"+root);
                ((ObjectNode)shape.path("properties")).set(field,extension.at("/snapshotSchema/properties/"+root+"/properties/"+field));
                ((ArrayNode)shape.path("required")).add(field);
                shape.put("maxProperties",shape.path("properties").size());
            }
            for(JsonNode fact:extension.path("facts")) {
                String pointer=fact.path("schemaPointer").asText();
                if(pointer.contains("/properties/customFields")||pointer.endsWith("/properties/definitionSnapshotHash"))
                    ((ArrayNode)op.path("facts")).add(fact);
            }
            ObjectNode base=(ObjectNode)DomainRuleG1CompletionContractTest.witness(op.path("snapshotSchema"));
            base.withObject("/context").put("empCodigo",scope.path("tenantId").asInt());
            base.withObject("/context").set("definitionSnapshotHash",scope.path("definitionSnapshotHash"));
            for(String root:List.of("draft","current")) {
                ObjectNode fields=scope.path("positive").path(root).path("customFields").deepCopy();
                fields.put("processo_sei","x".repeat(2000)); // exact declared length; no unbounded extension
                base.withObject("/"+root).set("customFields",fields);
            }
            assertThat(DomainRuleFactCatalogCandidateContractTest.errors(single(op))).isEmpty();
            assertThat(errors(single(op),base)).isEmpty();
            var arrays=new java.util.LinkedHashMap<String,JsonNode>();
            base.path("neighborhood").fields().forEachRemaining(e->arrays.put(e.getKey(),e.getValue().get(0)));
            assertThat(arrays).hasSize(4);
            int low=1,high=10001;
            while(low+1<high) {int middle=(low+high)/2;if(withinEngineBudget(expand(base,arrays,middle)))low=middle;else high=middle;}
            assertThat(errors(single(op),expand(base,arrays,low))).isEmpty();
            assertThat(errors(single(op),expand(base,arrays,high))).anyMatch(e->e.startsWith("engine fact limits:"));
            evidence.addObject().put("tenantId",scope.path("tenantId").asText()).put("syntheticExtension",true)
                    .put("simultaneousCollections",4).put("equalReferenceRowsPerCollection",low)
                    .put("firstRejectedRowsPerCollection",high).put("customStringLengthPerState",2000);
        }
        assertThat(evidence).hasSize(2);
        java.nio.file.Files.writeString(Path.of("target/ontology-g1-custom-capacity-reference.json"),evidence.toPrettyString());
    }
    private static ObjectNode expand(ObjectNode base,Map<String,JsonNode> arrays,int size) {
        ObjectNode result=base.deepCopy();
        arrays.forEach((key,item)->{var target=result.withObject("/neighborhood").putArray(key);for(int i=0;i<size;i++)target.add(item.deepCopy());});
        return result;
    }
    private static boolean withinEngineBudget(JsonNode facts) {
        try {
            new PraxisJsonLogicEngine().validateResultValue(facts,org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits.DEFAULT);
            return true;
        } catch(org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException exceeded) {
            assertThat(exceeded.getCode()).isEqualTo(org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode.RULE_LIMIT_EXCEEDED);
            return false;
        }
    }
}
