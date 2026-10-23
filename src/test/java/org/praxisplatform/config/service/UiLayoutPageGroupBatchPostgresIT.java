package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.repository.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Negative storage fixtures exercise the real SQL prefix; these are not valid authoring journeys. */
@Tag("neon")
@EnabledIfSystemProperty(named = "praxis.page.p3a.neon.it", matches = "true")
class UiLayoutPageGroupBatchPostgresIT extends UiLayoutPageGroupPostgresFixture {
  @Test
  void bytePrefixPreservesExactBoundaryOversizedAndMissingWorkspacesWithoutSkippingIdentities() throws Exception {
    try (var host = new UiLayoutPageGroupHostFixture("p3a-prefix-" + UUID.randomUUID())) {
      var prototypePage = host.pages.create(host.alice, host.alice.version(), new PageCreationCommand(
          UUID.randomUUID(), new PageSourceReference.Native("fixture:page", "r1"), "Prefix prototype")).receipt();
      var prototype = new PageCreationCodec().read(jdbc.queryForObject(
          "SELECT evidence_text FROM ui_page_creation_evidence WHERE page_id=?", String.class, prototypePage.pageId()));
      var seed = new UiLayoutPageGroupDenseFixture(host, prototype, UUID.randomUUID(), UUID.randomUUID(), 7);
      int mebibyte = 1024 * 1024;
      seed.malformedFrozenWorkspaceForSelectionProbe(0, 3 * mebibyte);
      seed.malformedFrozenWorkspaceForSelectionProbe(1, 5 * mebibyte);
      seed.malformedFrozenWorkspaceForSelectionProbe(2, 8 * mebibyte + 1);
      seed.missingSourceDraftForSelectionProbe(4);
      UUID oversizedRelease = seed.releaseId(2), missingDraftRelease = seed.releaseId(4);
      seed.insert();
      for (var expected : List.of(new long[] {0, 3L * mebibyte}, new long[] {1, 5L * mebibyte},
          new long[] {2, 8L * mebibyte + 1})) {
        assertThat(jdbc.queryForObject("""
            SELECT octet_length(d.draft_document::text)::bigint FROM ui_layout_release_head h
            JOIN ui_layout_release r ON r.id=h.active_release_id JOIN ui_layout_draft d ON d.id=r.source_draft_id
            WHERE h.tenant_id=? AND h.environment=? AND h.root_component_id=?
            """, Long.class, host.scope.tenant(), host.scope.environment(), seed.pageIds().get((int) expected[0]).toString()))
            .isEqualTo(expected[1]);
      }
      var headsBefore = headStates(host);
      long eventsBefore = events(host);
      var store = new UiLayoutPageGroupHeadStore(new NamedParameterJdbcTemplate(dataSource), transactions,
          repo(UiLayoutReleaseHeadRepository.class));
      var context = host.context(host.alice, host.groups);
      var expectedPrefixes = List.of(seed.pageIds().subList(0, 2), seed.pageIds().subList(2, 3),
          seed.pageIds().subList(3, 4), seed.pageIds().subList(4, 5), seed.pageIds().subList(5, 7));
      var observed = new ArrayList<UUID>();
      UUID after = null;
      for (var expected : expectedPrefixes) {
        UUID cursor = after;
        var page = transactions.execute(status -> store.activePages(context, cursor));
        assertThat(page).extracting(UiLayoutPageGroupHeadStore.ActivePage::pageId).containsExactlyElementsOf(expected);
        observed.addAll(page.stream().map(UiLayoutPageGroupHeadStore.ActivePage::pageId).toList());
        after = page.getLast().pageId();
      }
      UUID finalCursor = after;
      var terminal = transactions.execute(status -> store.activePages(context, finalCursor));
      assertThat(terminal).isEmpty();
      assertThat(observed).containsExactlyElementsOf(seed.pageIds());

      var verifier = new UiLayoutFrozenWorkspaceResolver(repo(UiLayoutReleaseRepository.class),
          repo(UiLayoutDraftRepository.class), repo(UiLayoutReleaseMemberRepository.class),
          repo(UiLayoutDefinitionRepository.class), repo(UiLayoutRevisionRepository.class),
          repo(UiLayoutAssignmentRevisionRepository.class), JSON, new CanonicalJsonHashService(JSON));
      for (var selected : List.of(new Selected(seed.pageIds().get(2), oversizedRelease),
          new Selected(seed.pageIds().get(4), missingDraftRelease))) {
        var invocation = host.context(host.alice, new UiLayoutTarget("praxis-dynamic-page", selected.page().toString())).invocation();
        assertThatThrownBy(() -> transactions.execute(status -> verifier.resolve(invocation, selected.release(), target -> {})))
            .isInstanceOf(UiLayoutLifecycleException.class)
            .hasMessage("Frozen native workspace is unavailable.");
      }
      assertThat(headStates(host)).isEqualTo(headsBefore);
      assertThat(events(host)).isEqualTo(eventsBefore);
      System.out.println("P3-A SQL byte-prefix proof identities=7 prefixes=2,1,1,1,2 boundaryBytes=8388608"
          + " oversizedFirstBytes=8388609 missingDraftPreserved=true canonicalVerifierDenied=true"
          + " negativeStorageFixture=true runtime=true triggers=true");
    }
  }

  private record Selected(UUID page, UUID release) {}

  private List<java.util.Map<String, Object>> headStates(UiLayoutPageGroupHostFixture host) {
    return jdbc.queryForList("SELECT id,active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND environment=? ORDER BY id",
        host.scope.tenant(), host.scope.environment());
  }

  private long events(UiLayoutPageGroupHostFixture host) {
    return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=? AND environment=?", Long.class,
        host.scope.tenant(), host.scope.environment());
  }
}
