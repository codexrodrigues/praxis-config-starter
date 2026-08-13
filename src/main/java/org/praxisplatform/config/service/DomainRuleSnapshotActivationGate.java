package org.praxisplatform.config.service;

import java.util.UUID;
import org.praxisplatform.config.domain.DomainRuleSnapshot;
import org.praxisplatform.config.domain.DomainRuleSnapshotHead;

/** Transactional extension point evaluated while the mutable RuleSet head is locked. */
public interface DomainRuleSnapshotActivationGate {
  void requireAllowed(UUID rolloutId, DomainRuleSnapshot target, DomainRuleSnapshotHead currentHead,
      String actorRef);

  void activationCompleted(UUID rolloutId, DomainRuleSnapshot target, String actorRef);

  static DomainRuleSnapshotActivationGate allowAll() {
    return new DomainRuleSnapshotActivationGate() {
      @Override public void requireAllowed(UUID rolloutId, DomainRuleSnapshot target,
          DomainRuleSnapshotHead currentHead, String actorRef) {}
      @Override public void activationCompleted(UUID rolloutId, DomainRuleSnapshot target,
          String actorRef) {}
    };
  }
}
