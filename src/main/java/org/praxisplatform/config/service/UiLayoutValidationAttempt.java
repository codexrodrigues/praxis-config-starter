package org.praxisplatform.config.service;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Server-owned identity and monotonic validation budget for one lifecycle entry.
 * Not an authority grant, HTTP model, transaction timeout or worker cancellation mechanism.
 * Config callers create one owning attempt. Internal correlated views preserve its exact clock,
 * initiating operation, correlation and lifetime across targets and nested reads.
 * Lifecycle callers apply cooperative entry/return and commit checks; blocked work is not interrupted.
 */
public final class UiLayoutValidationAttempt implements AutoCloseable {
  private final UiLayoutLifecycleInvocation invocation;
  private final Budget budget;
  private final boolean owner;
  private final AtomicBoolean closed = new AtomicBoolean();

  /** One monotonic clock and lifetime. Correlated views never allocate another allowance. */
  private static final class Budget {
    final UiLayoutLifecycleOperation operation;
    final UUID correlationRef = UUID.randomUUID();
    final LongSupplier ticker;
    final long startedAt, budgetNanos;
    final AtomicLong remaining;
    final AtomicBoolean beforeCommit = new AtomicBoolean();
    final Map<Object, Object> verified = new ConcurrentHashMap<>();
    Budget(UiLayoutLifecycleOperation operation, long nanos, LongSupplier ticker) {
      this.operation = operation; this.ticker = ticker; this.startedAt = ticker.getAsLong();
      this.budgetNanos = nanos; this.remaining = new AtomicLong(nanos);
    }
  }

  /** Start with an explicit server-selected budget; no operational default or client correlation is accepted. */
  public static UiLayoutValidationAttempt start(UiLayoutLifecycleInvocation invocation,
      UiLayoutLifecycleOperation operation, Duration budget) {
    return new UiLayoutValidationAttempt(invocation, operation, budget, System::nanoTime);
  }

  // Deterministic clock seam for same-package tests only; callers cannot substitute a civil clock.
  UiLayoutValidationAttempt(UiLayoutLifecycleInvocation invocation, UiLayoutLifecycleOperation operation,
      Duration budget, LongSupplier ticker) {
    this.invocation = Objects.requireNonNull(invocation, "invocation is required.");
    Objects.requireNonNull(operation, "operation is required.");
    Objects.requireNonNull(budget, "validation budget is required.");
    if (budget.isZero() || budget.isNegative()) throw new IllegalArgumentException("Validation budget must be positive.");
    final long nanos;
    try { nanos = budget.toNanos(); }
    catch (ArithmeticException exception) { throw new IllegalArgumentException("Validation budget exceeds monotonic range."); }
    this.budget = new Budget(operation, nanos, Objects.requireNonNull(ticker, "monotonic ticker is required."));
    this.owner = true;
  }

  private UiLayoutValidationAttempt(UiLayoutLifecycleInvocation invocation, Budget budget) {
    this.invocation = invocation; this.budget = budget; this.owner = false;
  }

  /** Internal correlation only; a new target still needs every canonical admission. */
  UiLayoutValidationAttempt correlate(UiLayoutLifecycleInvocation next) {
    requireActive();
    if (next == null || !invocation.actorRef().equals(next.actorRef())
        || !invocation.tenantId().equals(next.tenantId())
        || !invocation.administrativeUnit().equals(next.administrativeUnit())
        || !invocation.environment().equals(next.environment())
        || !invocation.contextVersion().equals(next.contextVersion())) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.CONTEXT_STALE,
          "Nested validation changed the authority correlation.");
    }
    return new UiLayoutValidationAttempt(next, budget);
  }

  boolean sharesBudget(UiLayoutValidationAttempt other) { return other != null && budget == other.budget; }

  void enterBeforeCommit() {
    budget.beforeCommit.set(true);
  }

  boolean inBeforeCommit() {
    return budget.beforeCommit.get();
  }

  @SuppressWarnings("unchecked")
  <T> T verified(Object key) {
    requireActive();
    return (T) budget.verified.get(key);
  }

  void markVerified(Object key, Object summary) {
    requireActive();
    budget.verified.put(key, summary);
  }

  public UiLayoutLifecycleInvocation invocation() { return invocation; }
  /** Initiating operation, retained even if diagnosis requires a nested historical visibility check. */
  public UiLayoutLifecycleOperation operation() { return budget.operation; }
  public UUID correlationRef() { return budget.correlationRef; }

  /**
   * Remaining budget in this process's monotonic clock domain. Never increases or reopens.
   * Subtraction tolerates nanoTime wrap for intervals within the positive signed-long budget.
   * A negative elapsed interval is outside that domain and fails closed.
   */
  public Duration remainingBudget() {
    if (closed.get() || budget.remaining.get() == 0) return Duration.ZERO;
    long elapsed = budget.ticker.getAsLong() - budget.startedAt;
    long available = elapsed < 0 || elapsed >= budget.budgetNanos ? 0 : budget.budgetNanos - elapsed;
    return Duration.ofNanos(budget.remaining.updateAndGet(previous -> Math.min(previous, available)));
  }

  /** Call before and after extensions, and before accepting a result. Does not interrupt blocked work. */
  public void requireActive() {
    if (remainingBudget().isZero()) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Lifecycle validation attempt is no longer active.");
    }
  }

  /** Permanently disallow further acceptance; resource/process termination belongs to the executor. */
  @Override public void close() {
    closed.set(true);
    if (owner) budget.remaining.set(0);
  }
}
