package org.praxisplatform.config.service;

/**
 * Governed scope used to resolve Java implementations admitted for snapshot publication.
 *
 * @param tenantId server-resolved tenant identity
 * @param environment governed deployment environment
 * @param ownerServiceKey domain host that will consume the snapshot
 */
public record DomainRuleImplementationScope(
    String tenantId,
    String environment,
    String ownerServiceKey) {

  /** Normalizes the complete scope and rejects implicit defaults. */
  public DomainRuleImplementationScope {
    tenantId = required(tenantId, "tenantId");
    environment = required(environment, "environment");
    ownerServiceKey = required(ownerServiceKey, "ownerServiceKey");
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
