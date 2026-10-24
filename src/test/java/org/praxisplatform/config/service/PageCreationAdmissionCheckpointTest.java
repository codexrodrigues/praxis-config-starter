package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Real service/Spring completion sequencing, synthetic storage: no database rollback claim. */
@Tag("unit")
class PageCreationAdmissionCheckpointTest {
  private static final UiLayoutLifecycleInvocation INVOCATION = new UiLayoutLifecycleInvocation(
      "alice", "tenant", "unit", "test", "c1", new UiLayoutCompositionRegistration(
          UiLayoutPageRevisionReproductionTest.PAGE, List.of(UiLayoutPageRevisionReproductionTest.PAGE)));
  private static final PageCreationContext CONTEXT = new PageCreationContext("app", "/pages", INVOCATION);
  private static final PageCreationCommand COMMAND = new PageCreationCommand(UUID.randomUUID(),
      new PageSourceReference.Native("fixture:page", "r1"), "Copy");

  @Test void completesOnlyAfterSixFreshJointAdmissionsAcrossLockWorkAndCommit() throws Exception {
    var fixture = new Fixture(0);
    assertThat(fixture.pages.create(() -> "alice", "c1", COMMAND).created()).isTrue();
    assertThat(fixture.events).containsExactly("admit1", "lock", "admit2", "insert", "draft",
        "admit3", "admit4", "receipt", "admit5", "admit6", "commit");
    assertThat(fixture.tx.durable).isTrue();
    verify(fixture.bridge, never()).require(any(), any(), any());
    verify(fixture.bridge, times(6)).requireCreation(any(), eq("c1"), eq(CONTEXT), eq(COMMAND.source()));
    // Bootstrap plus the independent post-receipt I/O re-attestation remain.
    verify(fixture.bridge, times(2)).resolve(any(), eq("c1"));
  }

  @Test void denialAtEveryCheckpointPreventsCommitAndSuccessfulOutcome() throws Exception {
    for (int deniedCheckpoint = 1; deniedCheckpoint <= 6; deniedCheckpoint++) {
      var fixture = new Fixture(deniedCheckpoint);
      assertThatThrownBy(() -> fixture.pages.create(() -> "alice", "c1", COMMAND))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
              failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
      assertThat(fixture.admissions).isEqualTo(deniedCheckpoint);
      assertThat(fixture.tx.durable).isFalse();
      assertThat(fixture.tx.rolledBack).isTrue();
      assertThat(fixture.events).doesNotContain("commit");
      if (deniedCheckpoint <= 2) assertThat(fixture.events).doesNotContain("insert", "draft");
      else assertThat(fixture.events).contains("insert", "draft");
      if (deniedCheckpoint >= 5) assertThat(fixture.events).contains("receipt");
    }
  }

  @Test void jointCallbackRetainsEntryAndExitBudgetChecks() throws Exception {
    var fixture = new Fixture(0);
    var clock = new AtomicLong();
    try (var attempt = new UiLayoutValidationAttempt(INVOCATION, UiLayoutLifecycleOperation.CREATE_DRAFT,
        Duration.ofNanos(100), clock::get)) {
      var validation = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);
      doAnswer(call -> { clock.set(101); return null; }).when(fixture.bridge).requireCreation(any(), any(), any(), any());
      assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fixture.pages, "authorizeCreate", (java.security.Principal) () -> "alice", "c1", CONTEXT, COMMAND, validation))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
              failure -> assertThat(failure.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
      verify(fixture.bridge, times(1)).requireCreation(any(), eq("c1"), eq(CONTEXT), eq(COMMAND.source()));
      assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(fixture.pages, "authorizeCreate", (java.security.Principal) () -> "alice", "c1", CONTEXT, COMMAND, validation))
          .isInstanceOf(UiLayoutLifecycleException.class);
      verify(fixture.bridge, times(1)).requireCreation(any(), eq("c1"), eq(CONTEXT), eq(COMMAND.source()));
    }
  }

  private static final class Fixture {
    final List<String> events = new ArrayList<>();
    final FakeTransaction tx = new FakeTransaction(events);
    final PageCatalogStore store = mock(PageCatalogStore.class, withSettings().mockMaker("mock-maker-inline"));
    final PageCatalogLifecycleBridge bridge = mock(PageCatalogLifecycleBridge.class, withSettings().mockMaker("mock-maker-inline"));
    final PageCreationService pages;
    int admissions;
    PageCreationEvidence inserted;
    Fixture(int denyAt) throws Exception {
      var mapper = new ObjectMapper().findAndRegisterModules();
      var commands = mock(UiLayoutLifecycleCommandService.class);
      var reads = mock(UiLayoutLifecycleReadService.class);
      pages = new PageCreationService(mock(PageCreationHost.class),
          new NamedParameterJdbcTemplate(mock(DataSource.class)), new TransactionTemplate(tx),
          new CanonicalJsonHashService(mapper), mapper, mock(UiLayoutDraftWorkspaceSource.class),
          mock(UiLayoutBaselineMetadataAdmission.class), mock(UiLayoutLifecycleStructureValidator.class),
          (operation, invocation) -> Duration.ofSeconds(40), mock(UiLayoutDraftRepository.class),
          mock(UiLayoutReleaseRepository.class), mock(UiLayoutReleaseMemberRepository.class),
          mock(UiLayoutDefinitionRepository.class), mock(UiLayoutRevisionRepository.class),
          mock(UiLayoutAssignmentRevisionRepository.class), () -> commands, () -> reads);
      ReflectionTestUtils.setField(pages, "store", store);
      ReflectionTestUtils.setField(pages, "bridge", bridge);
      when(bridge.resolve(any(), eq("c1"))).thenReturn(CONTEXT);
      doAnswer(call -> {
        events.add("admit" + ++admissions);
        if (admissions == denyAt) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "revoked");
        return null;
      }).when(bridge).requireCreation(any(), eq("c1"), eq(CONTEXT), eq(COMMAND.source()));
      doAnswer(call -> { events.add("lock"); return null; }).when(store).lock(any(), any());
      when(store.byKey(any(), any())).thenReturn(Optional.empty());
      var document = UiLayoutPageRevisionReproductionTest.page();
      var seed = new UiLayoutDraftWorkspaceSeed.TargetSeed(UiLayoutPageRevisionReproductionTest.PAGE,
          UiLayoutPageRevisionReproductionTest.DESCRIPTOR, UiLayoutPageRevisionReproductionTest.PATCH,
          "fixture:page", document, UiLayoutMetadataTestFixtures.nativeMetadata(document, "alice", "unit", "c1"));
      when(bridge.capture(any(), any(), any())).thenReturn(new PageCreationEvidence.SourceSnapshot(seed, document, null));
      doAnswer(call -> { inserted = call.getArgument(0); events.add("insert"); tx.provisional = true; return null; })
          .when(store).insert(any());
      when(commands.createReservedPageDraft(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
        events.add("draft"); return null;
      });
      when(reads.draftByCreationKey(any(), any(), any(), any(), any())).thenAnswer(call -> {
        events.add("receipt");
        return new UiLayoutDraftReceipt(inserted.draftId().toString(), inserted.target(), "DRAFT", "etag", Instant.EPOCH, Instant.EPOCH);
      });
    }
  }

  private static final class FakeTransaction extends AbstractPlatformTransactionManager {
    final List<String> events;
    boolean provisional, durable, rolledBack;
    FakeTransaction(List<String> events) { this.events = events; }
    @Override protected Object doGetTransaction() { return new Object(); }
    @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
    @Override protected void doCommit(DefaultTransactionStatus status) { durable = provisional; events.add("commit"); }
    @Override protected void doRollback(DefaultTransactionStatus status) { provisional = false; rolledBack = true; }
  }
}
