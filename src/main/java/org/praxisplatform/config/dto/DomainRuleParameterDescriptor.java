package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.praxisplatform.rules.contract.RuleParameterValueOrigin;

/** Read-only projection of the Engine-owned parameter contract and resolved values. */
public record DomainRuleParameterDescriptor(
    String name, RuleParameterType type, boolean required, boolean nullable, boolean readOnly,
    String unit, @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode defaultValue, BigDecimal minimum, BigDecimal maximum,
    List<JsonNode> allowedValues, @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode declaredValue, JsonNode effectiveValue,
    RuleParameterValueOrigin origin) {}
