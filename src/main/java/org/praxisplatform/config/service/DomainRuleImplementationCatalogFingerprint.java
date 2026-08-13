package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Canonical digest shared by snapshot approval and host runtime status. */
public final class DomainRuleImplementationCatalogFingerprint {
  private DomainRuleImplementationCatalogFingerprint() {}

  public static String sha256(
      ObjectMapper objectMapper,
      DomainRuleImplementationScope scope,
      Collection<RuleImplementationRef> implementations) {
    Objects.requireNonNull(objectMapper, "objectMapper is required");
    Objects.requireNonNull(scope, "scope is required");
    if (implementations == null) {
      throw new IllegalArgumentException("implementations are required");
    }
    List<RuleImplementationRef> ordered = implementations.stream()
        .sorted(Comparator.comparing(RuleImplementationRef::implementationKey)
            .thenComparing(RuleImplementationRef::implementationVersion))
        .toList();
    ObjectNode catalog = objectMapper.createObjectNode();
    catalog.put("tenantId", scope.tenantId());
    catalog.put("environment", scope.environment());
    catalog.put("ownerServiceKey", scope.ownerServiceKey());
    catalog.set("implementations", objectMapper.valueToTree(ordered));
    return PraxisCanonicalJson.sha256(catalog);
  }
}
