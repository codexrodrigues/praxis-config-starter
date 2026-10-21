package org.praxisplatform.config.service;

/** Serializes the complete lifecycle of one tenant/environment/RuleSet, including absent heads. */
@FunctionalInterface
public interface DomainRuleSnapshotScopeLock {
  void lock(String tenantId, String environment, String ruleSetKey);
}
