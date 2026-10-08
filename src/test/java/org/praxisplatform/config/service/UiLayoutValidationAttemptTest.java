package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutValidationAttemptTest {
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "fixture");
  private final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("actor", "tenant", "org",
      "test", "ctx", new UiLayoutCompositionRegistration(root, List.of(root)));
  private final AtomicLong ticks = new AtomicLong(100);
  private UiLayoutValidationAttempt attempt(long nanos) {
    return new UiLayoutValidationAttempt(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofNanos(nanos), ticks::get);
  }
  private void inactive(UiLayoutValidationAttempt value) {
    assertThatThrownBy(value::requireActive).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
  }
  @Test void keepsInvocationOperationAndServerGeneratedCorrelationAcrossChecks() {
    var value = attempt(10); var correlation = value.correlationRef();
    ticks.addAndGet(3); value.requireActive();
    assertThat(value.invocation()).isSameAs(invocation);
    assertThat(value.operation()).isEqualTo(UiLayoutLifecycleOperation.CREATE_DRAFT);
    assertThat(value.correlationRef()).isEqualTo(correlation).isNotNull();
    assertThat(attempt(10).correlationRef()).isNotEqualTo(correlation);
  }
  @Test void repeatedTargetChecksConsumeTheSameBudgetWithoutRestartingIt() {
    var value = attempt(10); ticks.addAndGet(4);
    assertThat(value.remainingBudget()).isEqualTo(Duration.ofNanos(6));
    ticks.addAndGet(4); value.requireActive();
    assertThat(value.remainingBudget()).isEqualTo(Duration.ofNanos(2));
    ticks.addAndGet(2); inactive(value);
  }
  @Test void equalityAtTheDeadlineIsExpired() {
    var value = attempt(1); ticks.incrementAndGet();
    assertThat(value.remainingBudget()).isZero(); inactive(value);
  }
  @Test void expiredAttemptCannotReopenIfTheClockMovesBack() {
    var value = attempt(10); ticks.addAndGet(11); inactive(value);
    ticks.set(100); assertThat(value.remainingBudget()).isZero(); inactive(value);
  }
  @Test void remainingBudgetNeverIncreasesUnderAnOutOfOrderObservation() {
    var value = attempt(10); ticks.addAndGet(7);
    assertThat(value.remainingBudget()).isEqualTo(Duration.ofNanos(3));
    ticks.addAndGet(-4); assertThat(value.remainingBudget()).isEqualTo(Duration.ofNanos(3));
  }
  @Test void negativeElapsedIntervalFailsClosed() {
    var value = attempt(10); ticks.decrementAndGet(); inactive(value);
  }
  @Test void signedNanoTimeWrapPreservesTheElapsedInterval() {
    ticks.set(Long.MAX_VALUE - 5); var value = attempt(10); ticks.set(Long.MIN_VALUE + 3);
    assertThat(value.remainingBudget()).isEqualTo(Duration.ofNanos(1));
    ticks.incrementAndGet(); inactive(value);
  }
  @Test void closePermanentlyInvalidatesTheSharedAttempt() {
    var value = attempt(10); value.close(); value.close(); ticks.addAndGet(1);
    assertThat(value.remainingBudget()).isZero(); inactive(value);
  }
  @Test void rejectsNonPositiveOrUnrepresentableBudget() {
    for (var budget : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
      assertThatThrownBy(() -> UiLayoutValidationAttempt.start(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, budget))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
  @Test void requiresInvocationOperationBudgetAndTicker() {
    assertThatThrownBy(() -> UiLayoutValidationAttempt.start(null, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> UiLayoutValidationAttempt.start(invocation, null, Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> UiLayoutValidationAttempt.start(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new UiLayoutValidationAttempt(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(1), null)).isInstanceOf(NullPointerException.class);
  }
}
