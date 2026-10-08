package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring transaction lifecycle with modeled pending writes; not PostgreSQL/JPA proof. */
@Tag("unit")
class UiLayoutLifecycleAuthorityFreshnessTest {
  private static final UiLayoutTarget ROOT = new UiLayoutTarget("praxis-table", "orders");
  private static final Principal ACTOR = () -> "author";

  enum Mutation { REVISION, ASSIGNMENT, FREEZE, SUBMIT, APPROVE, PUBLISH, WITHDRAW, ROLLBACK }
  enum ContextChange { ACTOR, TENANT, UNIT, ENVIRONMENT, VERSION, COMPOSITION }

  @Test void expiredBudgetAtActualOuterCommitRollsBackAndClosesTheAttempt() {
    Fixture f = new Fixture(); var ticks = new java.util.concurrent.atomic.AtomicLong();
    var attempt = new UiLayoutValidationAttempt(original(), UiLayoutLifecycleOperation.WITHDRAW_RELEASE,
        java.time.Duration.ofNanos(10), ticks::get);
    f.forcedValidation = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);
    {
      denied(() -> f.transactions.execute(status -> {
        f.call(Mutation.WITHDRAW);
        attempt.requireActive(); // Inner return must not close a participating command.
        ticks.set(10);
        return true;
      }), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    }
    assertRollback(f);
    assertThat(attempt.remainingBudget()).isZero();
  }

  @Test void successfulParticipatingCommandKeepsAttemptUntilOuterCompletion() {
    Fixture f = new Fixture(); var ticks = new java.util.concurrent.atomic.AtomicLong();
    var attempt = new UiLayoutValidationAttempt(original(), UiLayoutLifecycleOperation.WITHDRAW_RELEASE,
        java.time.Duration.ofNanos(10), ticks::get);
    f.forcedValidation = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);
    {
      f.transactions.execute(status -> { f.call(Mutation.WITHDRAW); attempt.requireActive(); return true; });
    }
    assertThat(f.manager.commits).isEqualTo(1);
    assertThat(f.manager.rollbacks).isZero();
    assertThat(attempt.remainingBudget()).isZero();
  }

  @Test void expiredCallbackDuringMutationRollsBackBeforeReturn() {
    Fixture f = new Fixture(); var ticks = new java.util.concurrent.atomic.AtomicLong();
    var attempt = new UiLayoutValidationAttempt(original(), UiLayoutLifecycleOperation.WITHDRAW_RELEASE,
        java.time.Duration.ofNanos(10), ticks::get);
    f.afterWrite = () -> ticks.set(10);
    f.forcedValidation = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);
    {
      denied(() -> f.call(Mutation.WITHDRAW), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    }
    assertRollback(f);
    assertThat(attempt.remainingBudget()).isZero();
  }

  @ParameterizedTest @EnumSource(Mutation.class)
  void revocationBetweenResolutionAndTransactionStopsEveryMutationBeforeWork(Mutation mutation) {
    Fixture f = new Fixture();
    f.manager.onBegin = () -> f.revoked.set(true);
    denied(() -> f.call(mutation), UiLayoutLifecycleException.Code.DENIED);
    assertThat(f.manager.commits).isZero();
    assertThat(f.manager.rollbacks).isEqualTo(1);
    assertThat(f.manager.committedHead).isEqualTo(f.originalHead);
    verifyNoInteractions(f.core);
  }

  @ParameterizedTest @EnumSource(Mutation.class)
  void contextChangeBetweenResolutionAndTransactionStopsEveryMutationBeforeWork(Mutation mutation) {
    Fixture f = new Fixture();
    f.manager.onBegin = () -> f.current.set(changed(ContextChange.UNIT));
    denied(() -> f.call(mutation), UiLayoutLifecycleException.Code.CONTEXT_STALE);
    assertThat(f.manager.commits).isZero();
    assertThat(f.manager.rollbacks).isEqualTo(1);
    assertThat(f.manager.committedHead).isEqualTo(f.originalHead);
    verifyNoInteractions(f.core);
  }

  @ParameterizedTest @EnumSource(ContextChange.class)
  void fullInvocationChangesDuringWorkRollbackTheHead(ContextChange change) {
    Fixture f = new Fixture();
    f.afterWrite = () -> f.current.set(changed(change));
    denied(() -> f.call(Mutation.WITHDRAW), UiLayoutLifecycleException.Code.CONTEXT_STALE);
    assertRollback(f);
  }

  @Test void revocationDuringWorkRollsBackTheHead() {
    Fixture f = new Fixture();
    f.afterWrite = () -> f.revoked.set(true);
    denied(() -> f.call(Mutation.WITHDRAW), UiLayoutLifecycleException.Code.DENIED);
    assertRollback(f);
  }

  @Test void technicalAdmissionFailureDuringWorkIsSanitizedAndRollsBack() {
    Fixture f = new Fixture();
    f.afterWrite = () -> f.technicalFailure.set(true);
    assertThatThrownBy(() -> f.call(Mutation.WITHDRAW))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class, error -> {
          assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
          assertThat(error.getMessage()).doesNotContain("private-credential");
          assertThat(error.getCause()).isNull();
        });
    assertRollback(f);
  }

  @ParameterizedTest @EnumSource(ContextChange.class)
  void ambientTransactionRechecksFullInvocationBeforeItsActualCommit(ContextChange change) {
    Fixture f = new Fixture();
    denied(() -> f.transactions.execute(status -> {
      f.call(Mutation.WITHDRAW);
      assertThat(f.manager.commits).isZero();
      f.current.set(changed(change));
      return true;
    }), UiLayoutLifecycleException.Code.CONTEXT_STALE);
    assertRollback(f);
  }

  @Test void revocationAfterReturnStillRollsBackTheAmbientTransaction() {
    Fixture f = new Fixture();
    denied(() -> f.transactions.execute(status -> {
      f.call(Mutation.WITHDRAW);
      f.revoked.set(true);
      return true;
    }), UiLayoutLifecycleException.Code.DENIED);
    assertRollback(f);
  }

  @Test void unchangedAuthorityCommitsOneHeadMutationAndReleasesTransactionResources() {
    Fixture f = new Fixture();
    f.call(Mutation.WITHDRAW);
    assertThat(f.manager.commits).isEqualTo(1);
    assertThat(f.budgetSelections).hasValue(1);
    assertThat(f.manager.rollbacks).isZero();
    assertThat(f.manager.committedHead).isNull();
    assertThat(TransactionSynchronizationManager.hasResource(f.manager.key)).isFalse();
  }

  @Test void missingTransactionSynchronizationFailsBeforeAnyPersistence() {
    Fixture f = new Fixture();
    denied(() -> f.service(TransactionOperations.withoutTransaction()).withdraw(ACTOR, "ctx-1", ROOT,
        UiLayoutLifecycleCommandService.HeadWriteCondition.match(f.etag.toString()), "reason"),
        UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertThat(f.manager.committedHead).isEqualTo(f.originalHead);
    verifyNoInteractions(f.core);
  }

  @Test void caughtRevocationCannotCommitAfterAuthorityIsRestoredInTheAmbientTransaction() {
    Fixture f = new Fixture();
    f.afterWrite = () -> f.revoked.set(true);
    assertThatThrownBy(() -> f.transactions.execute(status -> {
      denied(() -> f.call(Mutation.WITHDRAW), UiLayoutLifecycleException.Code.DENIED);
      f.revoked.set(false);
      return true;
    })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
    assertRollback(f);
  }

  @Test void readOnlyTransactionFailsBeforeAnyPersistence() {
    Fixture f = new Fixture();
    f.transactions.setReadOnly(true);
    denied(() -> f.call(Mutation.WITHDRAW), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    assertRollback(f);
    verifyNoInteractions(f.core);
  }

  private static void denied(Runnable action, UiLayoutLifecycleException.Code code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        error -> assertThat(error.getCode()).isEqualTo(code));
  }

  private static void assertRollback(Fixture f) {
    assertThat(f.manager.commits).isZero();
    assertThat(f.manager.rollbacks).isEqualTo(1);
    assertThat(f.manager.committedHead).isEqualTo(f.originalHead);
    assertThat(TransactionSynchronizationManager.hasResource(f.manager.key)).isFalse();
  }

  private static UiLayoutLifecycleInvocation original() {
    return new UiLayoutLifecycleInvocation("author", "tenant-a", "procurement", "lab", "ctx-1",
        new UiLayoutCompositionRegistration(ROOT, List.of(ROOT)));
  }

  private static UiLayoutLifecycleInvocation changed(ContextChange change) {
    var original = original();
    return new UiLayoutLifecycleInvocation(change == ContextChange.ACTOR ? "other" : original.actorRef(),
        change == ContextChange.TENANT ? "tenant-b" : original.tenantId(),
        change == ContextChange.UNIT ? "other-unit" : original.administrativeUnit(),
        change == ContextChange.ENVIRONMENT ? "other-env" : original.environment(),
        change == ContextChange.VERSION ? "ctx-2" : original.contextVersion(),
        change == ContextChange.COMPOSITION ? new UiLayoutCompositionRegistration(ROOT,
            List.of(ROOT, new UiLayoutTarget("praxis-table", "other-child"))) : original.composition());
  }

  private static final class Fixture {
    final UUID originalHead = UUID.randomUUID();
    final UUID etag = UUID.randomUUID();
    final StateTransactionManager manager = new StateTransactionManager(originalHead);
    final TransactionTemplate transactions = new TransactionTemplate(manager);
    final UiLayoutLifecycleService core = mock(UiLayoutLifecycleService.class);
    final UiLayoutReleaseHeadRepository heads = mock(UiLayoutReleaseHeadRepository.class);
    final AtomicReference<UiLayoutLifecycleInvocation> current = new AtomicReference<>(original());
    final AtomicBoolean revoked = new AtomicBoolean();
    final AtomicBoolean technicalFailure = new AtomicBoolean();
    final java.util.concurrent.atomic.AtomicInteger budgetSelections = new java.util.concurrent.atomic.AtomicInteger();
    UiLayoutValidationContext forcedValidation;
    Runnable afterWrite = () -> {};

    Fixture() {
      when(heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(any(), any(), any(), any()))
          .thenReturn(java.util.Optional.of(UiLayoutReleaseHead.builder().headEtag(etag).activeReleaseId(originalHead).build()));
      when(core.withdraw(any(), any(), any(), any())).thenAnswer(call -> {
        ((State)TransactionSynchronizationManager.getResource(manager.key)).pendingHead = null;
        afterWrite.run();
        return UiLayoutReleaseHead.builder().headEtag(UUID.randomUUID()).activeReleaseId(null).build();
      });
    }

    UiLayoutLifecycleCommandService service(TransactionOperations operations) {
      var service = new UiLayoutLifecycleCommandService(core, (principal, version, root) -> current.get(),
          (operation, invocation) -> {
            if (technicalFailure.get()) throw new IllegalStateException("private-credential");
            if (revoked.get()) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "revoked");
          }, null, null, null, heads, new ObjectMapper(), operations,
          null, null, null, null, null, new CanonicalJsonHashService(new ObjectMapper()),
          null, null, null, null, (budgetOperation, budgetInvocation) -> {
            budgetSelections.incrementAndGet(); return java.time.Duration.ofMinutes(1);
          });
      if (forcedValidation != null) {
        service = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(forcedValidation).when(service).beginValidation(original(),
            UiLayoutLifecycleOperation.WITHDRAW_RELEASE, UiLayoutValidationPurpose.AUTHORING);
      }
      return service;
    }

    void call(Mutation mutation) {
      var service = service(transactions);
      switch (mutation) {
        case REVISION -> service.createRevision(ACTOR, "ctx-1", ROOT, UUID.randomUUID(), etag.toString(), null);
        case ASSIGNMENT -> service.createAssignment(ACTOR, "ctx-1", ROOT, UUID.randomUUID(), etag.toString(), null);
        case FREEZE -> service.freezeRelease(ACTOR, "ctx-1", ROOT, UUID.randomUUID(), etag.toString(), UUID.randomUUID());
        case SUBMIT -> service.submit(ACTOR, "ctx-1", ROOT, UUID.randomUUID(), etag.toString());
        case APPROVE -> service.approve(ACTOR, "ctx-1", ROOT, UUID.randomUUID(), etag.toString(), "reason");
        case PUBLISH -> service.publish(ACTOR, "ctx-1", ROOT,
            UiLayoutLifecycleCommandService.HeadWriteCondition.match(etag.toString()), UUID.randomUUID(), "reason");
        case WITHDRAW -> service.withdraw(ACTOR, "ctx-1", ROOT,
            UiLayoutLifecycleCommandService.HeadWriteCondition.match(etag.toString()), "reason");
        case ROLLBACK -> service.rollback(ACTOR, "ctx-1", ROOT,
            UiLayoutLifecycleCommandService.HeadWriteCondition.match(etag.toString()), UUID.randomUUID(), "reason");
      }
    }
  }

  private static final class State implements org.springframework.transaction.support.SmartTransactionObject {
    boolean rollbackOnly;
    @Override public boolean isRollbackOnly() { return rollbackOnly; }
    boolean active;
    UUID pendingHead;
  }

  private static final class StateTransactionManager extends AbstractPlatformTransactionManager {
    final Object key = new Object();
    UUID committedHead;
    int commits;
    int rollbacks;
    Runnable onBegin = () -> {};
    StateTransactionManager(UUID head) { committedHead = head; }
    @Override protected Object doGetTransaction() {
      var existing = TransactionSynchronizationManager.getResource(key);
      return existing != null ? existing : new State();
    }
    @Override protected boolean isExistingTransaction(Object transaction) { return ((State)transaction).active; }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
      State state = (State)transaction; state.active = true; state.pendingHead = committedHead;
      TransactionSynchronizationManager.bindResource(key, state); onBegin.run();
    }
    @Override protected void doCommit(DefaultTransactionStatus status) {
      committedHead = ((State)status.getTransaction()).pendingHead; commits++;
    }
    @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { ((State)status.getTransaction()).rollbackOnly = true; }
    @Override protected void doCleanupAfterCompletion(Object transaction) {
      ((State)transaction).active = false;
      TransactionSynchronizationManager.unbindResource(key);
    }
  }
}
