package org.praxisplatform.config.service;

import java.util.Collection;
import java.util.List;
import org.praxisplatform.rules.contract.RuleImplementationRef;

/**
 * Host-owned catalog of Java rule coordinates admitted by supply-chain policy.
 *
 * <p>The catalog is external to the publication payload. Product coordinates represent code
 * shipped by the target host. Customer coordinates must include {@code RuleExtensionTrust}
 * created only after signature, artifact digest and allowlist verification.</p>
 */
@FunctionalInterface
public interface DomainRuleImplementationCatalog {

  /**
   * Returns exact implementations admitted for one tenant, environment and owner host.
   * @param scope server-resolved publication scope
   * @return immutable or safely copyable implementation declarations; never {@code null}
   */
  Collection<RuleImplementationRef> allowedImplementations(DomainRuleImplementationScope scope);

  /**
   * Returns the canonical fail-closed catalog used when a host declares no Java capability.
   * @return deny-all implementation catalog
   */
  static DomainRuleImplementationCatalog denyAll() {
    return scope -> List.of();
  }
}
