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
@EnabledIfSystemProperty(named = "praxis.page.p3b.neon.it", matches = "true")
class PageRuntimeBatchPostgresIT extends PageRuntimePostgresFixture {
  @Test
  void bytePrefixPreservesExactBoundaryOversizedAndMissingWorkspacesWithoutSkippingIdentities() throws Exception {
    try (var host = new PageRuntimeHostFixture("p3b-prefix-" + UUID.randomUUID())) {
      var prototypePage = host.pages.create(host.alice, host.alice.version(), new PageCreationCommand(
          UUID.randomUUID(), new PageSourceReference.Native("fixture:page", "r1"), "Prefix prototype")).receipt();
      var prototype = new PageCreationCodec().read(jdbc.queryForObject(
          "SELECT evidence_text FROM ui_page_creation_evidence WHERE page_id=?", String.class, prototypePage.pageId()));
      var seed = new PageRuntimeDenseFixture(host, prototype, UUID.randomUUID(), UUID.randomUUID(), 7);
      int mebibyte = 1024 * 1024;
      seed.malformedFrozenWorkspaceForSelectionProbe(0, 3 * mebibyte);
      seed.malformedFrozenWorkspaceForSelectionProbe(1, 5 * mebibyte);
      seed.malformedFrozenWorkspaceForSelectionProbe(2, 8 * mebibyte + 1);
      seed.missingSourceDraftForSelectionProbe(4);
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
      var store = new PageRuntimeStore(new NamedParameterJdbcTemplate(dataSource), transactions);
      var context = host.context(host.alice, host.groups);
      var expectedPrefixes = List.of(seed.pageIds().subList(0, 2), seed.pageIds().subList(2, 3),
          seed.pageIds().subList(3, 4), seed.pageIds().subList(4, 5), seed.pageIds().subList(5, 7));
      var observed = new ArrayList<UUID>();
      transactions.executeWithoutResult(status -> {
        var pinned = store.vector(context, null, null).stream().filter(head -> head.release() != null).toList();
        assertThat(pinned).hasSize(7);
        int offset = 0;
        for (var expected : expectedPrefixes) {
          var batch = store.batch(pinned, offset);
          assertThat(batch).extracting(PageRuntimeStore.Head::pageId).containsExactlyElementsOf(expected);
          observed.addAll(batch.stream().map(PageRuntimeStore.Head::pageId).toList());
          offset += batch.size();
        }
        assertThat(offset).isEqualTo(pinned.size());
      });
      assertThat(observed).containsExactlyElementsOf(seed.pageIds());
      for (UUID malformedPage : List.of(seed.pageIds().get(2), seed.pageIds().get(4))) {
        assertThatThrownBy(() -> host.runtime.published(host.alice, "c1", malformedPage))
            .isInstanceOf(UiLayoutLifecycleException.class).extracting("code")
            .isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      }
      assertThat(headStates(host)).isEqualTo(headsBefore);
      assertThat(events(host)).isEqualTo(eventsBefore);
      System.out.println("P3-B pinned batch-prefix proof identities=7 prefixes=2,1,1,1,2 boundaryBytes=8388608"
          + " oversizedFirstBytes=8388609 missingDraftPreserved=true runtimePublishedDenied503=true"
          + " negativeStorageFixture=true runtime=true triggers=true");
    }
  }

  @Test void corruptRootMetadataRejectsOtherwiseCorrelatedPublicationWithoutPartialCatalog() throws Exception {
    try (var host = new PageRuntimeHostFixture("p3b-capture-" + UUID.randomUUID())) {
      UUID group = UUID.randomUUID();
      publishGroup(host, group);
      var prototypePage = host.pages.create(host.alice, "c1", new PageCreationCommand(UUID.randomUUID(),
          new PageSourceReference.Native("fixture:page", "r1"), "Capture prototype")).receipt();
      var prototype = new PageCreationCodec().read(jdbc.queryForObject(
          "SELECT evidence_text FROM ui_page_creation_evidence WHERE page_id=?", String.class, prototypePage.pageId()));
      var seed = new PageRuntimeDenseFixture(host, prototype, group, group, 2);
      seed.corruptRootMetadataRawHashForAdmissionProbe(1);
      seed.insert();
      var before = headStates(host);
      long eventBaseline = events(host);
      var admittedRoots = new java.util.HashSet<String>();
      host.structureCallback = (invocation, target) -> admittedRoots.add(target.componentId());
      assertThat(host.runtime.published(host.alice, "c1", seed.pageIds().getFirst()).pageId())
          .isEqualTo(seed.pageIds().getFirst());
      admittedRoots.clear();
      var mapper = JSON.copy().addMixIn(org.springframework.http.ProblemDetail.class,
          org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
      var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
          new org.praxisplatform.config.controller.PageRuntimeController(host.runtime))
          .setControllerAdvice(new org.praxisplatform.config.controller.UiLayoutResolutionExceptionHandler())
          .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/praxis/config/pages")
          .principal(host.alice).header("X-Praxis-Context-Version", "c1").header("If-None-Match", "*").param("limit", "1"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("ETag"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.receiptEtag").doesNotExist())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items").doesNotExist());
      assertThat(admittedRoots).contains(seed.pageIds().getFirst().toString());
      assertThat(admittedRoots).doesNotContain(seed.pageIds().getLast().toString());
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
          "/api/praxis/config/pages/" + seed.pageIds().getLast() + "/published")
          .principal(host.alice).header("X-Praxis-Context-Version", "c1").header("If-None-Match", "*"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("ETag"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.receiptEtag").doesNotExist())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.document").doesNotExist());
      assertThat(headStates(host)).isEqualTo(before);
      assertThat(events(host)).isEqualTo(eventBaseline);
      System.out.println("P3-B corrupt root capture otherwiseCorrelated=true firstPageStructurePassed=true catalog503=true published503=true partialBody=false runtime=true triggers=true");
    }
  }

  private List<java.util.Map<String, Object>> headStates(PageRuntimeHostFixture host) {
    return jdbc.queryForList("SELECT id,active_release_id,head_etag,row_version FROM ui_layout_release_head WHERE tenant_id=? AND environment=? ORDER BY id",
        host.scope.tenant(), host.scope.environment());
  }

  private long events(PageRuntimeHostFixture host) {
    return jdbc.queryForObject("SELECT count(*) FROM ui_layout_release_event WHERE tenant_id=? AND environment=?", Long.class,
        host.scope.tenant(), host.scope.environment());
  }
  private void publishGroup(PageRuntimeHostFixture host, UUID group) {
    var draft = host.commands.createDraft(host.alice, "c1", host.groups, UUID.randomUUID().toString());
    UUID draftId = UUID.fromString(draft.draft().draftRef());
    var revision = host.commands.createRevision(host.alice, "c1", host.groups, draftId, draft.draft().draftEtag(),
        new UiLayoutLifecycleCommandService.RevisionInput(UUID.randomUUID(), host.groups, PageRuntimeHostFixture.groupsDocument(group).toString(), "Density groups"));
    var assignment = host.commands.createAssignment(host.alice, "c1", host.groups, draftId, revision.nextDraft().draft().draftEtag(),
        new UiLayoutLifecycleCommandService.AssignmentInput(UUID.randomUUID(), host.groups, "TENANT",
            new UiLayoutAudienceSelectorCommand(host.scope.tenant(), null, null, null, null, null), (short)0, "Density groups"));
    var frozen = host.commands.freezeRelease(host.alice, "c1", host.groups, draftId, assignment.nextDraft().draft().draftEtag(), UUID.randomUUID());
    UUID release = UUID.fromString(frozen.release().releaseRef());
    var submitted = host.commands.submit(host.alice, "c1", host.groups, release, frozen.review().reviewEtag());
    host.commands.approve(host.alice, "c1", host.groups, release, submitted.reviewEtag(), "Density seed groups");
    host.commands.publish(host.alice, "c1", host.groups, UiLayoutLifecycleCommandService.HeadWriteCondition.firstPublish(), release, "Density seed groups");
  }
}
