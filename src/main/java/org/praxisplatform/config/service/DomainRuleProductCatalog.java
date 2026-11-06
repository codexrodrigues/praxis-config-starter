package org.praxisplatform.config.service;

import java.util.Optional;
import org.praxisplatform.rules.contract.RuleSetRef;

/** Server-owned product provenance. Request payloads and customer definitions cannot register products. */
@FunctionalInterface
public interface DomainRuleProductCatalog {
  Optional<DomainRuleProductProfile> find(DomainRuleImplementationScope scope, RuleSetRef identity);
}
