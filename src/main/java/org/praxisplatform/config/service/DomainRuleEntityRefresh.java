package org.praxisplatform.config.service;

import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.springframework.data.jpa.repository.JpaContext;

/** Refreshes lifecycle inputs after scope serialization, including a caller's existing JPA context. */
public class DomainRuleEntityRefresh {
    private final JpaContext context;

    public DomainRuleEntityRefresh(JpaContext context) {
        this.context = java.util.Objects.requireNonNull(context);
    }

    public void requireCleanEntry() {
        var manager = context.getEntityManagerByManagedType(DomainRuleDefinition.class);
        if (manager.unwrap(org.hibernate.Session.class).isDirty()) {
            throw new org.praxisplatform.config.exception.ConfigurationIngestionException(
                    "Domain rule lifecycle requires a Config persistence context without pending external changes");
        }
    }

    public void requireCleanSnapshotEntry() {
        var manager = context.getEntityManagerByManagedType(org.praxisplatform.config.domain.DomainRuleSnapshotHead.class);
        if (manager.unwrap(org.hibernate.Session.class).isDirty()) {
            throw new org.praxisplatform.config.exception.ConfigurationIngestionException(
                    "RuleSet lifecycle requires a Config persistence context without pending external changes");
        }
    }

    public void snapshotHead(org.praxisplatform.config.domain.DomainRuleSnapshotHead head) {
        refreshLifecycle(head, org.praxisplatform.config.domain.DomainRuleSnapshotHead.class);
    }
    public void rolloutPolicy(org.praxisplatform.config.domain.DomainRuleRolloutPolicy policy) {
        refreshLifecycle(policy, org.praxisplatform.config.domain.DomainRuleRolloutPolicy.class);
    }
    public void rolloutPolicyHead(org.praxisplatform.config.domain.DomainRuleRolloutPolicyHead head) {
        refreshLifecycle(head, org.praxisplatform.config.domain.DomainRuleRolloutPolicyHead.class);
    }
    public void snapshotRollout(org.praxisplatform.config.domain.DomainRuleSnapshotRollout rollout) {
        refreshLifecycle(rollout, org.praxisplatform.config.domain.DomainRuleSnapshotRollout.class);
    }

    public void definition(DomainRuleDefinition definition) {
        refresh(definition, DomainRuleDefinition.class);
    }

    public void materialization(DomainRuleMaterialization materialization) {
        refresh(materialization, DomainRuleMaterialization.class);
    }

    private void refreshLifecycle(Object entity, Class<?> managedType) {
        // Read without an optimistic row-lock check first, then load/lock the current row.
        // Lifecycle writers own the RuleSet scope lock; observational probes lock only this row.
        // Both reject dirty entry state before loading mutable inputs.
        context.getEntityManagerByManagedType(managedType)
                .refresh(entity, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    private void refresh(Object entity, Class<?> managedType) {
        // Resolve the owning persistence unit; never assume that the host's primary EM is Config.
        var manager = context.getEntityManagerByManagedType(managedType);
        // Never flush a caller's stale dirty entities before rereading the committed lifecycle.
        manager.refresh(entity);
    }
}
