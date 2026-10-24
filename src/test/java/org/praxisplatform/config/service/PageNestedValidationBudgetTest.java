package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.*;
import org.springframework.transaction.support.TransactionOperations;

@Tag("unit")
class PageNestedValidationBudgetTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final UiLayoutTarget shell = new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString());
  private final UiLayoutTarget page = new UiLayoutTarget("praxis-dynamic-page", UUID.randomUUID().toString());
  private final UiLayoutLifecycleInvocation outer = invocation(shell), nested = invocation(page);
  private final AtomicLong ticks = new AtomicLong();
  private final AtomicInteger policies = new AtomicInteger(), admissions = new AtomicInteger();
  private final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
  private final UiLayoutLifecycleInvocationProvider invocations = mock(UiLayoutLifecycleInvocationProvider.class);
  private UiLayoutLifecycleInvocation invocation(UiLayoutTarget root) {
    return new UiLayoutLifecycleInvocation("author", "tenant", "unit", "env", "ctx", new UiLayoutCompositionRegistration(root, List.of(root)));
  }
  private UiLayoutValidationAttempt owner() {
    return new UiLayoutValidationAttempt(outer, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofSeconds(40), ticks::get);
  }
  private UiLayoutValidationContext context(UiLayoutValidationAttempt attempt) {
    return new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);
  }
  private UiLayoutValidationBudgetPolicy policy() {
    return (operation, invocation) -> { policies.incrementAndGet(); return Duration.ofHours(1); };
  }
  private UiLayoutLifecycleReadService reader(UiLayoutLifecycleAdmission admission) {
    when(invocations.resolve(any(), eq("ctx"), eq(page))).thenReturn(nested);
    return new UiLayoutLifecycleReadService(invocations, admission, drafts, mock(UiLayoutReleaseRepository.class),
        mock(UiLayoutReleaseReviewRepository.class), mock(UiLayoutReleaseHeadRepository.class),
        mock(UiLayoutDefinitionRepository.class), mock(UiLayoutRevisionRepository.class),
        mock(UiLayoutAssignmentRevisionRepository.class), mapper, hashes, mock(UiLayoutLifecycleStructureValidator.class), policy());
  }
  private void unavailable(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
  }
  private UiLayoutDraft draft() {
    return UiLayoutDraft.builder().id(UUID.randomUUID()).rootComponentType(page.componentType()).rootComponentId(page.componentId())
        .state("DRAFT").draftEtag(UUID.randomUUID()).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
  }

  @Test void receiptReadUsesOwnerForEveryAdmissionAndReturnsWithoutClosingIt() {
    try (var owner = owner()) {
      var reader = reader((operation, invocation) -> {
        admissions.incrementAndGet(); ticks.addAndGet(Duration.ofSeconds(1).toNanos());
        try (var current = UiLayoutValidationScope.borrow(invocation)) {
          assertThat(current.correlationRef()).isEqualTo(owner.correlationRef());
          assertThat(current.operation()).isEqualTo(UiLayoutLifecycleOperation.CREATE_DRAFT);
        }
      });
      var draft = draft();
      when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(any(),any(),any(),any(),any(),any()))
          .thenReturn(Optional.of(draft));
      var result = reader.draftByCreationKey(() -> "author", "ctx", page, "key", context(owner));
      assertThat(result.draftRef()).isEqualTo(draft.getId().toString());
      assertThat(admissions.get()).isEqualTo(3);
      assertThat(policies.get()).isZero(); // A fresh one-hour child policy must never be selected.
      assertThat(owner.remainingBudget()).isEqualTo(Duration.ofSeconds(37));
      owner.requireActive();
      unavailable(() -> UiLayoutValidationScope.borrow(nested));
    }
  }

  @Test void receiptReadExpiredBeforeEntryCannotResolveAuthorityOrReadPersistence() {
    var reader = reader((operation, invocation) -> admissions.incrementAndGet());
    try (var owner = owner()) {
      ticks.set(Duration.ofSeconds(40).toNanos());
      unavailable(() -> reader.draftByCreationKey(() -> "author", "ctx", page, "key", context(owner)));
      assertThat(admissions.get()).isZero(); assertThat(policies.get()).isZero();
      verifyNoInteractions(drafts); verify(invocations, never()).resolve(any(),any(),any());
    }
  }

  @Test void receiptExpiryDuringPersistencePreventsAcceptingItsProjection() {
    var reader = reader((operation, invocation) -> admissions.incrementAndGet());
    var draft = draft();
    when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(any(),any(),any(),any(),any(),any()))
        .thenAnswer(call -> { ticks.set(Duration.ofSeconds(40).toNanos()); return Optional.of(draft); });
    try (var owner = owner()) {
      unavailable(() -> reader.draftByCreationKey(() -> "author", "ctx", page, "key", context(owner)));
      assertThat(policies.get()).isZero(); unavailable(owner::requireActive);
      unavailable(() -> UiLayoutValidationScope.borrow(nested));
    }
  }

  @Test void reservedCommandInitialAdmissionCannotRenewOrOutliveTheParent() {
    var core = mock(UiLayoutLifecycleService.class);
    var transactions = mock(TransactionOperations.class);
    when(invocations.resolve(any(), eq("ctx"), eq(page))).thenReturn(nested);
    try (var owner = owner()) {
      var command = new UiLayoutLifecycleCommandService(core, invocations, (operation, invocation) -> {
        try (var observed = UiLayoutValidationScope.borrow(invocation)) {
          assertThat(observed.correlationRef()).isEqualTo(owner.correlationRef());
          assertThat(observed.operation()).isEqualTo(UiLayoutLifecycleOperation.CREATE_DRAFT);
        }
        ticks.set(Duration.ofSeconds(40).toNanos());
      }, null, drafts, null, mapper, transactions, null, null, null, null, null, hashes,
          null, null, null, null, policy());
      unavailable(() -> command.createReservedPageDraft(() -> "author", "ctx", page, "key", UUID.randomUUID(), context(owner)));
      assertThat(policies.get()).isZero(); verifyNoInteractions(transactions, core, drafts);
      unavailable(() -> UiLayoutValidationScope.borrow(nested));
    }
  }

  @Test void creationLookupNormalizesUnexpectedProviderAndAdmissionCodesBeforePersistence() {
    for (var code : List.of(UiLayoutLifecycleException.Code.NOT_FOUND, UiLayoutLifecycleException.Code.INVALID_STATE)) {
      for (boolean providerFailure : List.of(true, false)) {
        var reader = reader((operation, invocation) -> {
          if (!providerFailure) throw new UiLayoutLifecycleException(code, "private-source-detail");
        });
        if (providerFailure) when(invocations.resolve(any(), eq("ctx"), eq(page)))
            .thenThrow(new UiLayoutLifecycleException(code, "private-source-detail"));
        try (var owner = owner()) {
          unavailable(() -> reader.draftByCreationKey(() -> "author", "ctx", page, "key", context(owner)));
          owner.requireActive(); verifyNoInteractions(drafts);
        }
        reset(invocations);
      }
    }
  }

  @Test void initialReadAdmissionFailureIsSanitizedAndClosesOnlyItsView() {
    var reader = reader((operation, invocation) -> { throw new IllegalStateException("private-source-detail"); });
    try (var owner = owner()) {
      assertThatThrownBy(() -> reader.draftByCreationKey(() -> "author", "ctx", page, "key", context(owner)))
          .isInstanceOf(UiLayoutLifecycleException.class).hasMessageNotContaining("private-source-detail").hasNoCause();
      owner.requireActive(); verifyNoInteractions(drafts);
      unavailable(() -> UiLayoutValidationScope.borrow(nested));
    }
  }
}
