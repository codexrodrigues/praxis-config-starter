package org.praxisplatform.config.service;

import java.util.Objects;
import java.util.function.Supplier;

/** Server SPI view. Purpose views retain the exact same attempt; they do not grant content visibility. */
public record UiLayoutValidationContext(UiLayoutValidationAttempt attempt, UiLayoutValidationPurpose purpose) {
  public UiLayoutValidationContext {
    Objects.requireNonNull(attempt, "validation attempt is required.");
    Objects.requireNonNull(purpose, "validation purpose is required.");
  }
  public UiLayoutValidationContext forPurpose(UiLayoutValidationPurpose next) {
    return new UiLayoutValidationContext(attempt, next);
  }
  public void require(UiLayoutLifecycleInvocation invocation) {
    if (!attempt.invocation().equals(invocation)) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE,
          "Validation attempt does not match the invocation.");
    }
    attempt.requireActive();
  }
  public void requirePurpose(UiLayoutValidationPurpose... allowed) {
    for (var value : allowed) if (purpose == value) return;
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Validation purpose is not admitted.");
  }
  public <T> T call(UiLayoutLifecycleInvocation invocation, Supplier<T> action) {
    require(invocation);
    try { return action.get(); }
    finally { require(invocation); }
  }
  public void run(UiLayoutLifecycleInvocation invocation, Runnable action) {
    call(invocation, () -> { action.run(); return Boolean.TRUE; });
  }
  static UiLayoutValidationContext begin(UiLayoutValidationBudgetPolicy policy,
      UiLayoutLifecycleInvocation invocation, UiLayoutLifecycleOperation operation, UiLayoutValidationPurpose purpose) {
    try {
      return new UiLayoutValidationContext(UiLayoutValidationAttempt.start(invocation, operation,
          Objects.requireNonNull(policy, "validation budget policy is required.").budget(operation, invocation)), purpose);
    } catch (UiLayoutLifecycleException exception) {
      throw new UiLayoutLifecycleException(exception.getCode(), "Validation budget could not be admitted.");
    } catch (RuntimeException exception) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Validation budget is unavailable.");
    }
  }
}
