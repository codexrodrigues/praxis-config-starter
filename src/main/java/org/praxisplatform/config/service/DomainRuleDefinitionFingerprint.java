package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Computes the canonical hash shared by definition approvals and snapshot provenance. */
@RequiredArgsConstructor
public class DomainRuleDefinitionFingerprint {
  private final ObjectMapper objectMapper;

  public String sha256(DomainRuleDefinition source) {
    try {
      ObjectNode content = objectMapper.createObjectNode();
      content.put("definitionId", source.getId().toString());
      content.put("definitionKey", source.getRuleKey());
      content.put("version", source.getVersion());
      content.set("definition", objectMapper.readTree(source.getDefinition()));
      content.set("parameters", objectMapper.readTree(source.getParameters()));
      content.set("condition", source.getCondition() == null
          ? objectMapper.nullNode() : objectMapper.readTree(source.getCondition()));
      content.set("governance", objectMapper.readTree(source.getGovernance()));
      return PraxisCanonicalJson.sha256(content);
    } catch (JsonProcessingException exception) {
      throw new ConfigurationIngestionException(
          "A governed rule definition contains invalid JSON", exception);
    }
  }
}
