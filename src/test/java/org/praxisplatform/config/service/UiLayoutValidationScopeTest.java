package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutValidationScopeTest {
  private final UiLayoutTarget shell = new UiLayoutTarget("praxis-app-shell", "00000000-0000-4000-8000-000000000001");
  private final UiLayoutTarget page = new UiLayoutTarget("praxis-dynamic-page", "00000000-0000-4000-8000-000000000002");
  private final UiLayoutLifecycleInvocation invocation = invocation("actor", "tenant", "unit", "env", "context", shell);
  private final AtomicLong ticks = new AtomicLong();
  private UiLayoutLifecycleInvocation invocation(String actor, String tenant, String unit, String env, String version, UiLayoutTarget root) {
    return new UiLayoutLifecycleInvocation(actor, tenant, unit, env, version, new UiLayoutCompositionRegistration(root, List.of(root)));
  }
  private UiLayoutValidationAttempt owner() {
    return new UiLayoutValidationAttempt(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(40), ticks::get);
  }
  private void unavailable(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
  }

  @Test void correlatedViewsKeepExactClockOperationCorrelationAndClosure() {
    var owner = owner();
    ticks.set(Duration.ofSeconds(17).toNanos());
    var child = owner.correlate(invocation("actor", "tenant", "unit", "env", "context", page));
    assertThat(child.invocation().rootTarget()).isEqualTo(page);
    assertThat(child.correlationRef()).isEqualTo(owner.correlationRef());
    assertThat(child.operation()).isEqualTo(owner.operation());
    assertThat(child.remainingBudget()).isEqualTo(Duration.ofSeconds(23));
    child.close();
    unavailable(child::requireActive);
    owner.requireActive();
    var retained = owner.correlate(child.invocation());
    owner.close();
    unavailable(retained::requireActive);
    unavailable(() -> owner.correlate(invocation));
  }

  @Test void childCannotReopenDeadlineOrUseAnotherAuthority() {
    var owner = owner();
    var child = owner.correlate(invocation);
    ticks.set(Duration.ofSeconds(40).toNanos());
    unavailable(child::requireActive);
    ticks.set(0); // Even a regressing injected clock cannot renew either view.
    unavailable(owner::requireActive);
    unavailable(() -> child.correlate(invocation));
    owner.close();
  }

  @Test void everyAuthorityDimensionIsBoundButCompositionIsNotAnAuthorityGrant() {
    try (var owner = owner()) {
      for (var other : List.of(invocation("other","tenant","unit","env","context",page),
          invocation("actor","other","unit","env","context",page),
          invocation("actor","tenant","other","env","context",page),
          invocation("actor","tenant","unit","other","context",page),
          invocation("actor","tenant","unit","env","other",page))) {
        assertThatThrownBy(() -> owner.correlate(other)).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.CONTEXT_STALE));
      }
      owner.requireActive();
    }
  }

  @Test void scopeRestoresPreviousFrameAfterReentrancyAndFailure() {
    try (var owner = owner(); var scope = UiLayoutValidationScope.bind(owner)) {
      try (var child = owner.correlate(invocation); var nested = UiLayoutValidationScope.bind(child)) {
        assertThatThrownBy(() -> new UiLayoutValidationContext(child, UiLayoutValidationPurpose.AUTHORING)
            .run(invocation, () -> { throw new IllegalStateException("original"); }))
            .isInstanceOf(IllegalStateException.class).hasMessage("original");
        try (var observed = UiLayoutValidationScope.borrow(invocation)) {
          assertThat(observed.correlationRef()).isEqualTo(owner.correlationRef());
        }
      }
      try (var observed = UiLayoutValidationScope.borrow(invocation)) { observed.requireActive(); }
    }
    unavailable(() -> UiLayoutValidationScope.borrow(invocation));
  }

  @Test void scopeRejectsRenewalAndWrongCloseOrderWithoutLosingTheOwner() {
    try (var owner = owner(); var other = owner()) {
      var scope = UiLayoutValidationScope.bind(owner);
      unavailable(() -> UiLayoutValidationScope.bind(other));
      var nested = UiLayoutValidationScope.bind(owner);
      unavailable(scope::close);
      nested.close(); scope.close();
      unavailable(() -> UiLayoutValidationScope.borrow(invocation));
    }
  }

  @Test void callbackTransportIsLexicalAndNeverInheritedByAnotherThread() throws Exception {
    try (var owner = owner()) {
      var context = new UiLayoutValidationContext(owner, UiLayoutValidationPurpose.AUTHORING);
      context.run(invocation, () -> {
        try (var observed = UiLayoutValidationScope.borrow(invocation)) {
          assertThat(observed.correlationRef()).isEqualTo(owner.correlationRef());
        }
        var observed = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
          try { UiLayoutValidationScope.borrow(invocation); }
          catch (Throwable failure) { observed.set(failure); }
        });
        thread.start();
        try { thread.join(1000); } catch (InterruptedException failure) { throw new AssertionError(failure); }
        assertThat(thread.isAlive()).isFalse();
        assertThat(observed.get()).isInstanceOf(UiLayoutLifecycleException.class);
      });
      unavailable(() -> UiLayoutValidationScope.borrow(invocation));
    }
  }

  @Test void nestedCallbackExpiryStopsAcceptanceAndRemovesTheAmbientFrame() {
    try (var owner = owner()) {
      var context = new UiLayoutValidationContext(owner, UiLayoutValidationPurpose.AUTHORING);
      unavailable(() -> context.run(invocation, () -> ticks.set(Duration.ofSeconds(40).toNanos())));
      unavailable(() -> UiLayoutValidationScope.borrow(invocation));
      unavailable(owner::requireActive);
    }
  }
}
