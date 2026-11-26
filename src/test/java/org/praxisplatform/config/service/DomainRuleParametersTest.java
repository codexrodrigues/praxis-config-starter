package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.rules.contract.*;

@Tag("unit")
class DomainRuleParametersTest {
  private final ObjectMapper mapper = new ObjectMapper();

  static RuleParameterTemplate template(ObjectMapper mapper) throws Exception {
    return new RuleParameterTemplate("test:grant-share", "1",
        RuleExecutorRef.jsonLogic(mapper.readTree("{\"<=\":[{\"var\":\"request.amount\"},100]}")),
        RuleDecision.DENY, "LIMIT", List.of("request.amount"), List.of(
            new RuleParameterDefinition("limit", RuleParameterType.NUMBER, true, false, false,
                "BRL", IntNode.valueOf(100), BigDecimal.ZERO, BigDecimal.valueOf(200), List.of(),
                List.of("/<=/1"))));
  }

  static ObjectNode definition(ObjectMapper mapper) throws Exception {
    return mapper.createObjectNode().set("parameterTemplate", mapper.valueToTree(template(mapper)));
  }

  @Test void derivesDifferentConditionsWithoutChangingTemplateOrFacts() throws Exception {
    var definition = definition(mapper);
    var a = mapper.readTree("{\"parameterValues\":{\"limit\":50},\"hostContractVersion\":\"1\"}");
    var b = mapper.readTree("{\"parameterValues\":{\"limit\":150},\"hostContractVersion\":\"1\"}");
    var first = DomainRuleParameters.condition(mapper, definition, a, null);
    var second = DomainRuleParameters.draftCondition(mapper, definition, a, b, null);
    assertThat(first.at("/<=/1").intValue()).isEqualTo(50);
    assertThat(second.at("/<=/1").intValue()).isEqualTo(150);
    assertThat(second.at("/<=/0/var").asText()).isEqualTo("request.amount");
    assertThat(definition.at("/parameterTemplate/executor/expression/<=/1").intValue()).isEqualTo(100);
    assertThatThrownBy(() -> DomainRuleParameters.condition(mapper, definition, b, first))
        .isInstanceOf(ConfigurationIngestionException.class).hasMessageContaining("condition must match");
  }

  @Test void rejectsMetadataSchemaAndValuesTampering() throws Exception {
    var definition = definition(mapper);
    var base = mapper.readTree("{\"parameterValues\":{},\"hostContractVersion\":\"1\"}");
    assertThatThrownBy(() -> DomainRuleParameters.draftCondition(mapper, definition, base,
        mapper.readTree("{\"parameterValues\":{},\"hostContractVersion\":\"2\"}"), null))
        .hasMessageContaining("only change");
    for (String values : List.of("{\"limit\":201}", "{\"limit\":\"50\"}", "{\"unknown\":1}", "{\"limit\":null}")) {
      var parameters = mapper.createObjectNode().set("parameterValues", mapper.readTree(values));
      assertThatThrownBy(() -> DomainRuleParameters.condition(mapper, definition, parameters, null))
          .isInstanceOf(ConfigurationIngestionException.class);
    }
    ((ObjectNode) definition.path("parameterTemplate")).put("unknownSchemaField", true);
    assertThatThrownBy(() -> DomainRuleParameters.condition(mapper, definition, base, null))
        .hasMessageContaining("Invalid Engine parameter template");
  }

  @Test void projectsDefaultAndDeclaredOriginsAndCannotInventParametersWithoutTemplate() throws Exception {
    var response = response(mapper.createObjectNode());
    var defaults = DomainRuleParameters.catalog(mapper, response);
    assertThat(defaults.parameters()).hasSize(1);
    assertThat(defaults.parameters().getFirst().origin()).isEqualTo(RuleParameterValueOrigin.DEFAULT);
    assertThat(defaults.parameters().getFirst().declaredValue()).isNull();
    assertThat(defaults.parameters().getFirst().effectiveValue().intValue()).isEqualTo(100);
    var declaredResponse = response(mapper.readTree("{\"parameterValues\":{\"limit\":70}}"));
    assertThat(DomainRuleParameters.catalog(mapper, declaredResponse).parameters().getFirst().origin())
        .isEqualTo(RuleParameterValueOrigin.DECLARED);
    assertThatThrownBy(() -> DomainRuleParameters.condition(mapper, mapper.createObjectNode(),
        declaredResponse.parameters(), null)).hasMessageContaining("requires a governed");
  }

  @Test void compositionBindsApprovedTemplateAndValuesAndRejectsMissingSource() throws Exception {
    var template = template(mapper);
    var declared = mapper.createObjectNode().put("limit", 70);
    var source = new DomainRuleDefinition();
    source.setRuleKey("limit");
    source.setDefinition(definition(mapper).toString());
    source.setParameters(mapper.createObjectNode().set("parameterValues", declared).toString());
    source.setCondition(DomainRuleParameters.condition(mapper, definition(mapper),
        mapper.readTree(source.getParameters()), null).toString());
    var binding = new DecisionBinding("limit", "limit", DecisionSource.CUSTOMER, CompositionPolicy.PARAMETERIZE,
        null, List.of(), 1, true, null, null, List.of(),
        new RuleParameterization("test:grant-share", "1", declared));
    var set = new RuleSetDefinition(new RuleSetRef("test", "grant", "grant", "evaluate", 1),
        List.of("request"), List.of(new DecisionSlot("limit", DecisionStage.DOMAIN_DECISION,
        SlotCardinality.SINGLE, OverridePolicy.PARAMETERIZABLE, DecisionAggregationPolicy.SINGLE_RESULT, template, java.util.List.of())),
        List.of(binding), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    DomainRuleParameters.composition(mapper, set, List.of(source));
    assertThatThrownBy(() -> DomainRuleParameters.composition(mapper, set, List.of()))
        .hasMessageContaining("exactly one governed");
    source.setParameters("{\"parameterValues\":{\"limit\":60}}");
    source.setCondition(null);
    assertThatThrownBy(() -> DomainRuleParameters.composition(mapper, set, List.of(source)))
        .hasMessageContaining("approved source template and values");
  }

  @Test void projectsContract18MetadataAndInvariantsIntoParameterCatalogAndOmitsWhenAbsent() throws Exception {
    var emptyCatalog = DomainRuleParameters.catalog(mapper, response(mapper.createObjectNode()));
    assertThat(emptyCatalog.invariants()).isEmpty();
    assertThat(emptyCatalog.parameters().getFirst().metadata()).isNull();
    var serializedEmpty = mapper.valueToTree(emptyCatalog);
    assertThat(serializedEmpty.has("invariants")).isFalse();
    assertThat(serializedEmpty.at("/parameters/0").has("metadata")).isFalse();

    RuleParameterTemplate richTemplate = new RuleParameterTemplate(
        "test:bounded-range",
        "1.0",
        RuleExecutorRef.jsonLogic(mapper.readTree(
            "{\"and\":[{\"<=\":[{\"var\":\"request.days\"},60]},{\">=\":[{\"var\":\"request.days\"},1]}]}")),
        RuleDecision.DENY,
        "LIMIT",
        List.of("request.days"),
        List.of(
            new RuleParameterDefinition(
                "minDays", RuleParameterType.INTEGER, true, false, false, "days",
                IntNode.valueOf(1), BigDecimal.ZERO, BigDecimal.valueOf(100), List.of(),
                List.of("/and/1/>=/1"),
                new RuleParameterMetadata("Mínimo", "Dias mínimos", "Lei 8.112", "limites", 1)),
            new RuleParameterDefinition(
                "maxDays", RuleParameterType.INTEGER, true, false, false, "days",
                IntNode.valueOf(60), BigDecimal.ONE, BigDecimal.valueOf(365), List.of(),
                List.of("/and/0/<=/1"),
                new RuleParameterMetadata("Máximo", "Dias máximos", "Lei 8.112", "limites", 2))),
        List.of(RuleParameterInvariant.lte("MIN_LTE_MAX", "minDays <= maxDays", "minDays", "maxDays")));
    ObjectNode richDefinition = mapper.createObjectNode().set("parameterTemplate", mapper.valueToTree(richTemplate));
    var richResponse = new DomainRuleDefinitionResponse(UUID.randomUUID(), "tenant", "dev", "limit", 1,
        "business", "draft", null, null, null, null, null, null, null, richDefinition,
        mapper.createObjectNode().set("parameterValues", mapper.createObjectNode()),
        null, null, null, "authenticated", "author", null, null, null, null, null);

    var richCatalog = DomainRuleParameters.catalog(mapper, richResponse);
    assertThat(richCatalog.invariants()).hasSize(1);
    assertThat(richCatalog.invariants().getFirst().code()).isEqualTo("MIN_LTE_MAX");
    assertThat(richCatalog.parameters().get(0).metadata()).isNotNull();
    assertThat(richCatalog.parameters().get(0).metadata().legalBasis()).isEqualTo("Lei 8.112");
    assertThat(richCatalog.parameters().get(0).metadata().group()).isEqualTo("limites");
    var serializedRich = mapper.valueToTree(richCatalog);
    assertThat(serializedRich.at("/invariants/0/code").asText()).isEqualTo("MIN_LTE_MAX");
    assertThat(serializedRich.at("/parameters/0/metadata/label").asText()).isEqualTo("Mínimo");
    assertThat(serializedRich.at("/parameters/0/metadata").has("empty")).isFalse();
  }

  private DomainRuleDefinitionResponse response(JsonNode parameters) throws Exception {
    return new DomainRuleDefinitionResponse(UUID.randomUUID(), "tenant", "dev", "limit", 1,
        "business", "draft", null, null, null, null, null, null, null, definition(mapper),
        parameters, null, null, null, "authenticated", "author", null, null, null, null, null);
  }

}
