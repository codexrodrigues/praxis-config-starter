package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutValidationContextTest {
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "test-only");
  private final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("actor", "tenant", "org",
      "test", "ctx", new UiLayoutCompositionRegistration(root, List.of(root)));
  private final AtomicLong ticks = new AtomicLong();
  private UiLayoutValidationContext context() {
    return new UiLayoutValidationContext(new UiLayoutValidationAttempt(invocation, UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION,
        Duration.ofNanos(10), ticks::get), UiLayoutValidationPurpose.EVOLUTION_CURRENT_READ);
  }
  private void fails(Runnable work, UiLayoutLifecycleException.Code code) {
    assertThatThrownBy(work::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        error -> assertThat(error.getCode()).isEqualTo(code));
  }
  @Test void purposeViewsConsumeTheSameDeadlineAndKeepInitiatingOperation() {
    var current = context(); var historical = current.forPurpose(UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ);
    ticks.set(6); historical.require(invocation);
    assertThat(historical.attempt()).isSameAs(current.attempt());
    assertThat(historical.attempt().operation()).isEqualTo(UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION);
    assertThat(current.attempt().remainingBudget()).isEqualTo(Duration.ofNanos(4));
    ticks.set(10); fails(() -> current.require(invocation), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
  }
  @Test void lateSuccessfulCallbackCannotEscapeAndNextCallbackCannotStart() {
    var ctx = context();
    fails(() -> ctx.call(invocation, () -> { ticks.set(10); return "late"; }), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    var next = mock(Runnable.class);
    fails(() -> ctx.run(invocation, next), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verifyNoInteractions(next);
  }
  @Test void sameVersionDoesNotHideInvocationMismatch() {
    var other = new UiLayoutLifecycleInvocation("other", "tenant", "org", "test", "ctx", invocation.composition());
    fails(() -> context().require(other), UiLayoutLifecycleException.Code.CONTEXT_STALE);
  }
  @Test void historicalViewCannotInvokeCurrentNativeStructure() {
    var owner = mock(UiLayoutLifecycleStructureValidator.class);
    var historical = context().forPurpose(UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ);
    fails(() -> new UiLayoutBoundedStructureValidator(owner).validateTarget(invocation, root, historical), UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(owner);
  }
  @Test void lateNativeOwnerReturnIsRejectedByCanonicalWrapper() {
    var owner = mock(UiLayoutLifecycleStructureValidator.class); var ctx = context();
    doAnswer(call -> { ticks.set(10); return null; }).when(owner).validateTarget(invocation, root, ctx);
    fails(() -> new UiLayoutBoundedStructureValidator(owner).validateTarget(invocation, root, ctx), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
  }
  @Test void missingInvalidAndTechnicalBudgetPolicyFailWithoutLeakingDetails() {
    for (UiLayoutValidationBudgetPolicy policy : java.util.Arrays.<UiLayoutValidationBudgetPolicy>asList(null,
        (op, inv) -> null, (op, inv) -> Duration.ZERO, (op, inv) -> Duration.ofSeconds(Long.MAX_VALUE),
        (op, inv) -> { throw new IllegalStateException("private-budget-credential"); })) {
      assertThatThrownBy(() -> UiLayoutValidationContext.begin(policy, invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, UiLayoutValidationPurpose.AUTHORING))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class, error -> {
            assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
            assertThat(error.getMessage()).doesNotContain("private-budget-credential");
            assertThat(error.getCause()).isNull();
          });
    }
  }
}
