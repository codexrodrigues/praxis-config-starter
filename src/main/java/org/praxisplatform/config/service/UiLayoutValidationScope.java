package org.praxisplatform.config.service;

/**
 * Lexical transport of the existing validation attempt across SPIs without a context parameter.
 * Contains no authority result. Never inherited by workers or retained until transaction completion.
 */
final class UiLayoutValidationScope implements AutoCloseable {
  private static final ThreadLocal<UiLayoutValidationScope> CURRENT = new ThreadLocal<>();
  private final UiLayoutValidationScope previous;
  private final UiLayoutValidationAttempt value;
  private final Thread thread = Thread.currentThread();
  private boolean closed;

  static UiLayoutValidationScope bind(UiLayoutValidationAttempt value) {
    return new UiLayoutValidationScope(value);
  }

  private UiLayoutValidationScope(UiLayoutValidationAttempt value) {
    value.requireActive();
    previous = CURRENT.get();
    if (previous != null) {
      previous.value.requireActive();
      if (!previous.value.sharesBudget(value)) throw unavailable();
    }
    this.value = value;
    CURRENT.set(this);
  }

  static UiLayoutValidationAttempt borrow(UiLayoutLifecycleInvocation invocation) {
    var current = CURRENT.get();
    if (current == null) throw unavailable();
    return current.value.correlate(invocation);
  }

  @Override public void close() {
    if (closed) return;
    if (Thread.currentThread() != thread || CURRENT.get() != this) throw unavailable();
    closed = true;
    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
  }

  private static UiLayoutLifecycleException unavailable() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "The owning validation scope is unavailable.");
  }
}
