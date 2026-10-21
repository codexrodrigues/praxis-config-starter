package org.praxisplatform.config.service;

import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;

/** Transactional gate under the scope lock; currentHead is null only for verified bootstrap absence. */
public interface DomainRuleSnapshotActivationGate {
  void requireAllowed(UUID rolloutId, DomainRuleSnapshot target, DomainRuleSnapshotHead currentHead,
      String actorRef);

  void activationCompleted(UUID rolloutId, DomainRuleSnapshot target, String actorRef);

  /** Fail-closed default for read-only hosts with no operational rollout service. */
  static DomainRuleSnapshotActivationGate unavailable() {
    return new DomainRuleSnapshotActivationGate() {
      @Override public void requireAllowed(UUID rolloutId, DomainRuleSnapshot target,
          DomainRuleSnapshotHead currentHead, String actorRef) {
        throw new IllegalStateException("RuleSet activation governance is unavailable");
      }
      @Override public void activationCompleted(UUID rolloutId, DomainRuleSnapshot target, String actorRef) {
        throw new IllegalStateException("RuleSet activation governance is unavailable");
      }
    };
  }
}
