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

    public void definition(DomainRuleDefinition definition) {
        refresh(definition, DomainRuleDefinition.class);
    }

    public void materialization(DomainRuleMaterialization materialization) {
        refresh(materialization, DomainRuleMaterialization.class);
    }

    private void refresh(Object entity, Class<?> managedType) {
        // Resolve the owning persistence unit; never assume that the host's primary EM is Config.
        var manager = context.getEntityManagerByManagedType(managedType);
        // Never flush a caller's stale dirty entities before rereading the committed lifecycle.
        manager.refresh(entity);
    }
}
