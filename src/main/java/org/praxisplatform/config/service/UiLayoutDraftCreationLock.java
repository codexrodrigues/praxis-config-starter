package org.praxisplatform.config.service;

import org.praxisplatform.config.dto.UiLayoutTarget;

/** Serializes one exact draft-creation idempotency identity inside the Config transaction. */
@FunctionalInterface
public interface UiLayoutDraftCreationLock {
  void lock(String tenantId, String environment, UiLayoutTarget rootTarget, String actorRef, String idempotencyKey);
}
