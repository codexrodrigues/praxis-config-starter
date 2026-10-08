package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;

/** Server-attested authoring scope; it is resolved by the host and never deserialized from HTTP. */
public record UiLayoutLifecycleInvocation(
    String actorRef,
    String tenantId,
    String administrativeUnit,
    String environment,
    String contextVersion,
    UiLayoutCompositionRegistration composition) {
  public UiLayoutLifecycleInvocation {
    actorRef = required(actorRef, "actorRef");
    tenantId = required(tenantId, "tenantId");
    administrativeUnit = required(administrativeUnit, "administrativeUnit");
    environment = required(environment, "environment");
    contextVersion = required(contextVersion, "contextVersion");
    if (composition == null) throw new IllegalArgumentException("composition is required.");
  }

  public UiLayoutTarget rootTarget() {
    return composition.rootTarget();
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required.");
    return value.trim();
  }
}
