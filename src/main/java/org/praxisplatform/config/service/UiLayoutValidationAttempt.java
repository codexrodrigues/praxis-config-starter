package org.praxisplatform.config.service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Server-owned identity and monotonic validation budget for one lifecycle entry.
 * Not an authority grant, HTTP model, transaction timeout or worker cancellation mechanism.
 * Config callers must create once and pass the same instance through all targets and nested reads.
 * Lifecycle callers apply cooperative entry/return and commit checks; blocked work is not interrupted.
 */
public final class UiLayoutValidationAttempt implements AutoCloseable {
  private final UiLayoutLifecycleInvocation invocation;
  private final UiLayoutLifecycleOperation operation;
  private final UUID correlationRef;
  private final LongSupplier ticker;
  private final long startedAt;
  private final long budgetNanos;
  private final AtomicLong remaining;

  /** Start with an explicit server-selected budget; no operational default or client correlation is accepted. */
  public static UiLayoutValidationAttempt start(UiLayoutLifecycleInvocation invocation,
      UiLayoutLifecycleOperation operation, Duration budget) {
    return new UiLayoutValidationAttempt(invocation, operation, budget, System::nanoTime);
  }

  // Deterministic clock seam for same-package tests only; callers cannot substitute a civil clock.
  UiLayoutValidationAttempt(UiLayoutLifecycleInvocation invocation, UiLayoutLifecycleOperation operation,
      Duration budget, LongSupplier ticker) {
    this.invocation = Objects.requireNonNull(invocation, "invocation is required.");
    this.operation = Objects.requireNonNull(operation, "operation is required.");
    Objects.requireNonNull(budget, "validation budget is required.");
    if (budget.isZero() || budget.isNegative()) throw new IllegalArgumentException("Validation budget must be positive.");
    try { this.budgetNanos = budget.toNanos(); }
    catch (ArithmeticException exception) { throw new IllegalArgumentException("Validation budget exceeds monotonic range."); }
    this.ticker = Objects.requireNonNull(ticker, "monotonic ticker is required.");
    this.correlationRef = UUID.randomUUID();
    this.startedAt = ticker.getAsLong();
    this.remaining = new AtomicLong(budgetNanos);
  }

  public UiLayoutLifecycleInvocation invocation() { return invocation; }
  /** Initiating operation, retained even if diagnosis requires a nested historical visibility check. */
  public UiLayoutLifecycleOperation operation() { return operation; }
  public UUID correlationRef() { return correlationRef; }

  /**
   * Remaining budget in this process's monotonic clock domain. Never increases or reopens.
   * Subtraction tolerates nanoTime wrap for intervals within the positive signed-long budget.
   * A negative elapsed interval is outside that domain and fails closed.
   */
  public Duration remainingBudget() {
    if (remaining.get() == 0) return Duration.ZERO;
    long elapsed = ticker.getAsLong() - startedAt;
    long available = elapsed < 0 || elapsed >= budgetNanos ? 0 : budgetNanos - elapsed;
    return Duration.ofNanos(remaining.updateAndGet(previous -> Math.min(previous, available)));
  }

  /** Call before and after extensions, and before accepting a result. Does not interrupt blocked work. */
  public void requireActive() {
    if (remainingBudget().isZero()) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Lifecycle validation attempt is no longer active.");
    }
  }

  /** Permanently disallow further acceptance; resource/process termination belongs to the executor. */
  @Override public void close() { remaining.set(0); }
}
