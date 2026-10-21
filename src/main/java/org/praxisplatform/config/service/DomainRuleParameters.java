package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.parameters.RuleParameterResolver;

/** Config persistence boundary for the Engine-owned parameter contract. No second evaluator. */
final class DomainRuleParameters {
  private DomainRuleParameters() {}

  static RuleParameterTemplate template(ObjectMapper mapper, JsonNode definition) {
    JsonNode node = definition == null ? null : definition.get("parameterTemplate");
    if (node == null) return null;
    if (!node.isObject()) throw invalid("definition.parameterTemplate must be an object");
    try {
      return mapper.readerFor(RuleParameterTemplate.class)
          .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(node);
    } catch (Exception failure) {
      throw new ConfigurationIngestionException("Invalid Engine parameter template", failure);
    }
  }

  static ObjectNode values(ObjectMapper mapper, JsonNode parameters) {
    if (parameters == null || !parameters.isObject()) {
      throw invalid("Parameterized definitions require a parameters object");
    }
    JsonNode values = parameters.get("parameterValues");
    if (values == null) return mapper.createObjectNode();
    if (!values.isObject()) throw invalid("parameters.parameterValues must be an object");
    return ((ObjectNode) values).deepCopy();
  }

  static RuleParameterResolution resolve(RuleParameterTemplate template, ObjectNode values) {
    try {
      return RuleParameterResolver.resolve(template, values);
    } catch (RuntimeException failure) {
      throw new ConfigurationIngestionException("Parameter values do not satisfy the governed Engine template", failure);
    }
  }

  static JsonNode condition(ObjectMapper mapper, JsonNode definition, JsonNode parameters, JsonNode supplied) {
    RuleParameterTemplate template = template(mapper, definition);
    if (template == null) {
      if (parameters != null && parameters.has("parameterValues")) {
        throw invalid("parameterValues requires a governed parameterTemplate");
      }
      return supplied;
    }
    JsonNode derived = resolve(template, values(mapper, parameters)).executor().expression();
    if (supplied != null && !supplied.isNull() && !same(mapper, derived, supplied)) {
      throw invalid("condition must match the expression derived from the governed parameter template");
    }
    return derived;
  }

  static JsonNode draftCondition(ObjectMapper mapper, JsonNode definition, JsonNode baseParameters,
      JsonNode parameters, JsonNode supplied) {
    if (template(mapper, definition) != null) {
      ObjectNode base = baseParameters != null && baseParameters.isObject()
          ? ((ObjectNode) baseParameters).deepCopy() : mapper.createObjectNode();
      ObjectNode draft = ((ObjectNode) parameters).deepCopy();
      base.remove("parameterValues");
      draft.remove("parameterValues");
      if (!base.equals(draft)) throw invalid("Workspace edits may only change declared parameterValues");
    }
    return condition(mapper, definition, parameters, supplied);
  }

  static DomainRuleParameterCatalogResponse catalog(ObjectMapper mapper, DomainRuleDefinitionResponse definition) {
    RuleParameterTemplate template = template(mapper, definition.definition());
    List<DomainRuleParameterDescriptor> descriptors = List.of();
    if (template != null) {
      ObjectNode declared = values(mapper, definition.parameters());
      RuleParameterResolution resolved = resolve(template, declared);
      descriptors = template.parameters().stream().map(parameter -> new DomainRuleParameterDescriptor(
          parameter.name(), parameter.type(), parameter.required(), parameter.nullable(), parameter.readOnly(),
          parameter.unit(), parameter.defaultValue(), parameter.minimum(), parameter.maximum(),
          parameter.allowedValues(), declared.get(parameter.name()), resolved.effectiveValues().get(parameter.name()),
          resolved.origins().get(parameter.name()))).toList();
    }
    return new DomainRuleParameterCatalogResponse(definition.id(), definition.ruleKey(), definition.version(),
        "praxis.domain-rule-parameter-catalog.v1", template == null ? null : template.templateKey(),
        template == null ? null : template.templateVersion(), descriptors);
  }

  static void composition(ObjectMapper mapper, RuleSetDefinition ruleSet,
      List<org.praxisplatform.config.domain.DomainRuleDefinition> sources) {
    // Parameterized source semantics must be those actually approved, not caller-selected templates.
    for (var source : sources) {
      JsonNode definition = read(mapper, source.getDefinition());
      RuleParameterTemplate template = template(mapper, definition);
      if (template == null) continue;
      JsonNode parameters = read(mapper, source.getParameters());
      condition(mapper, definition, parameters, read(mapper, source.getCondition()));
      var bindings = ruleSet.bindings().stream().filter(b -> b.bindingKey().equals(source.getRuleKey())).toList();
      if (bindings.size() != 1 || bindings.getFirst().compositionPolicy() != CompositionPolicy.PARAMETERIZE) {
        throw invalid("Each parameterized source requires exactly one matching PARAMETERIZE binding");
      }
      var binding = bindings.getFirst();
      var slots = ruleSet.slots().stream().filter(s -> s.slotKey().equals(binding.slotKey())).toList();
      if (slots.size() != 1 || !same(mapper, slots.getFirst().parameterTemplate(), template)
          || !same(mapper, binding.parameterization(), new RuleParameterization(
              template.templateKey(), template.templateVersion(), values(mapper, parameters)))) {
        throw invalid("Snapshot parameterization must match its approved source template and values");
      }
    }
    for (var binding : ruleSet.bindings()) {
      if (binding.compositionPolicy() != CompositionPolicy.PARAMETERIZE) continue;
      var matching = sources.stream().filter(s -> s.getRuleKey().equals(binding.bindingKey())).toList();
      if (matching.size() != 1 || template(mapper, read(mapper, matching.getFirst().getDefinition())) == null) {
        throw invalid("PARAMETERIZE binding requires exactly one governed parameterized source");
      }
    }
  }

  static JsonNode read(ObjectMapper mapper, String value) {
    if (value == null) return null;
    try { return mapper.readTree(value); }
    catch (Exception failure) { throw new ConfigurationIngestionException("Invalid persisted parameter JSON", failure); }
  }

  private static boolean same(ObjectMapper mapper, Object first, Object second) {
    return PraxisCanonicalJson.sha256(mapper.valueToTree(first))
        .equals(PraxisCanonicalJson.sha256(mapper.valueToTree(second)));
  }

  private static ConfigurationIngestionException invalid(String message) {
    return new ConfigurationIngestionException(message);
  }
}
