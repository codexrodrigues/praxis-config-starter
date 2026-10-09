package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.UiLayoutLifecycleController;
import org.praxisplatform.config.controller.UiLayoutResolutionExceptionHandler;
import org.praxisplatform.config.domain.UiLayoutAssignmentRevision;
import org.praxisplatform.config.domain.UiLayoutDefinition;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseApproval;
import org.praxisplatform.config.domain.UiLayoutReleaseEvent;
import org.praxisplatform.config.domain.UiLayoutReleaseHead;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.domain.UiLayoutReleaseReview;
import org.praxisplatform.config.domain.UiLayoutRevision;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.UiLayoutAssignmentRevisionRepository;
import org.praxisplatform.config.repository.UiLayoutDefinitionRepository;
import org.praxisplatform.config.repository.UiLayoutDraftRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseApprovalRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseEventRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseHeadRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseMemberRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseRepository;
import org.praxisplatform.config.repository.UiLayoutReleaseReviewRepository;
import org.praxisplatform.config.repository.UiLayoutRevisionRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class UiLayoutLifecycleHttpRoundTripTest {
  private static final String ROOT_TYPE = "praxis-dynamic-form";
  private static final String ROOT_ID = "procurement-master-detail";
  private static final String CONTEXT = "ctx-1";
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final UiLayoutTarget root = new UiLayoutTarget(ROOT_TYPE, ROOT_ID);
  private final UiLayoutTarget child = new UiLayoutTarget("praxis-table", "purchase-orders");
  private final Principal publisher = () -> "publisher";
  private boolean omitCandidateTitle;

  @Test
  void corporateMapperCannotEraseNullPatchThroughRevisionFreezePublishAndPinnedRead() throws Exception {
    mapper.configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.READ_NULL_PROPERTIES, false)
        .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.WRITE_NULL_PROPERTIES, false);
    omitCandidateTitle = true; // Removal of an original title must be a null in the compiled merge patch.
    Fixture fixture = new Fixture();
    MockMvc http = fixture.http();
    ApprovedRelease release = createApprovedRelease(http, "corporate-null");
    http.perform(post("/api/praxis/config/ui-layouts/release-head/publish")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_NONE_MATCH, "*")
            .contentType("application/json").content(headBody(release.releaseRef(), "publish null patch")))
        .andExpect(status().isOk());
    assertThat(fixture.revisionRows).hasSize(2);
    var hashes = new CanonicalJsonHashService(mapper);
    fixture.revisionRows.values().forEach(revision -> {
      var patch = UiLayoutRevisionJsonInput.readDocument(revision.getPatchDocument(), () -> {});
      assertThat(patch.has("title")).isTrue();
      assertThat(patch.path("title").isNull()).isTrue();
      var omitted = (com.fasterxml.jackson.databind.node.ObjectNode) patch.deepCopy();
      omitted.remove("title");
      assertThat(revision.getContentHash()).isEqualTo(hashes.sha256Exact(patch))
          .isNotEqualTo(hashes.sha256Exact(omitted));
    });
    var reader = new JpaUiLayoutCompositionReleaseReader(fixture.heads, fixture.releases,
        fixture.members, fixture.assignments, fixture.revisions, fixture.definitions, mapper, hashes);
    var snapshot = reader.read("tenant-a", "lab", root);
    assertThat(snapshot.releaseRef()).isEqualTo(release.releaseRef());
    assertThat(snapshot.contributions()).hasSize(2).allSatisfy(contribution -> {
      assertThat(contribution.candidate().patch().has("title")).isTrue();
      assertThat(contribution.candidate().patch().path("title").isNull()).isTrue();
    });
    assertThat(mapper.readTree("{\"title\":null}").has("title")).isFalse();
  }

  @Test
  void malformedPersistedPatchDuringPublicationRemainsHistoricalConflictWithoutHeadWrites() throws Exception {
    String deep = "{}";
    for (int index = 0; index < 70; index++) deep = "{\"nested\":" + deep + "}";
    for (String corrupted : new String[] {"{\"x\":1,\"x\":2}", "{} {}", deep,
        "{\"x\":\"" + "x".repeat(UiLayoutDraftWorkspaceCodec.MAX_DOCUMENT_BYTES) + "\"}"}) {
      Fixture fixture = new Fixture();
      MockMvc http = fixture.http();
      ApprovedRelease release = createApprovedRelease(http, "corrupted-" + UUID.randomUUID());
      fixture.revisionRows.values().iterator().next().setPatchDocument(corrupted);
      int before = fixture.mutations.get();
      http.perform(post("/api/praxis/config/ui-layouts/release-head/publish")
              .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
              .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_NONE_MATCH, "*")
              .contentType("application/json").content(headBody(release.releaseRef(), "reject corrupted persisted patch")))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("INVALID_STATE"))
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      assertThat(fixture.headRows).isEmpty();
      assertThat(fixture.mutations.get()).isEqualTo(before);
    }
  }

  @Test
  void preciseAdjacentIntegerAndDecimalAreRejectedBeforeHttpRevisionPersistence() throws Exception {
    for (String token : new String[] {"9007199254740993", "-9007199254740993", "0.10000000000000001", "1e-400"}) {
      Fixture fixture = new Fixture();
      MockMvc http = fixture.http();
      var created = createFixtureDraft(http, "precision-" + token);
      var candidate = nativeDocument(root);
      ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("config"))
          .set("width", UiLayoutRevisionJsonInput.readDocument("{\"n\":" + token + "}", () -> {}).path("n"));
      String body = "{\"commandRef\":\"" + UUID.randomUUID() + "\",\"target\":" + target(root)
          + ",\"authoringDocument\":" + candidate + ",\"reason\":\"precision regression\"}";
      int before = fixture.mutations.get();
      http.perform(post("/api/praxis/config/ui-layouts/drafts/{id}/revisions", json(created).path("draft").path("draftRef").asText())
              .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
              .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, requiredEtag(created))
              .contentType("application/json").content(body))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("INVALID_RELEASE"));
      assertThat(fixture.revisionRows).isEmpty();
      assertThat(fixture.mutations.get()).isEqualTo(before);
    }
  }

  @Test void rejectsClientPatchBeforeAnyRevisionMutation() throws Exception {
    var fixture = new Fixture(); var http = fixture.http();
    var created = createFixtureDraft(http, "client-patch");
    var candidate = nativeDocument(child);
    var body = candidateBody(child, candidate); body.putObject("patchDocument");
    int before = fixture.mutations.get();
    postCandidate(http, json(created).path("draft").path("draftRef").asText(), requiredEtag(created), body)
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(fixture.mutations.get()).isEqualTo(before);
    assertThat(fixture.revisionRows).isEmpty();
  }

  @Test void successiveRevisionsCompileAgainstOriginalB0AndFreezeTheSelectedContribution() throws Exception {
    var fixture = new Fixture();
    var corpus = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/ai/contracts/table-native-revision-conformance.v1.json")));
    fixture.nativeTableBaseline = corpus.path("cases").get(0).path("baseline");
    var http = fixture.http(); var created = createFixtureDraft(http, "two-revisions");
    String draftRef = json(created).path("draft").path("draftRef").asText();
    var rootRevision = revision(http, draftRef, requiredEtag(created), root, "root");
    var c1 = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.nativeTableBaseline.deepCopy();
    var overrides1 = c1.path("config").path("columnProjection").path("overrides");
    ((com.fasterxml.jackson.databind.node.ObjectNode) overrides1.path("a")).remove("width");
    ((com.fasterxml.jackson.databind.node.ObjectNode) overrides1.path("b")).remove("visible");
    var first = postCandidate(http, draftRef, rootRevision.nextEtag(), candidateBody(child, c1))
        .andExpect(status().isCreated()).andReturn();
    var firstAssignment = assignment(http, draftRef, requiredEtag(first), child, "first");
    var c2 = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.nativeTableBaseline.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) c2.path("config").path("columnProjection").path("overrides").path("b")).remove("visible");
    var second = postCandidate(http, draftRef, firstAssignment.nextEtag(), candidateBody(child, c2))
        .andExpect(status().isCreated()).andReturn();
    var secondRef = UUID.fromString(json(second).path("revision").path("revisionRef").asText());
    var patch = mapper.readTree(fixture.revisionRows.get(secondRef).getPatchDocument());
    assertThat(patch.path("columnProjection").path("overrides").path("a").path("width").asText()).isEqualTo("120px");
    assertThat(patch.path("columnProjection").path("overrides").path("b").get("visible").isNull()).isTrue();
    var childState = json(second).path("nextDraft").path("targets").get(1);
    assertThat(childState.path("baseline").path("document")).isEqualTo(fixture.nativeTableBaseline);
    assertThat(childState.path("working").path("document")).isEqualTo(c2);
    assertThat(childState.path("selectedAssignment").isNull()).isTrue();
    var childAssignment = assignment(http, draftRef, requiredEtag(second), child, "second");
    var rootAssignment = assignment(http, draftRef, childAssignment.nextEtag(), root, "root");
    var frozen = http.perform(post("/api/praxis/config/ui-layouts/drafts/{id}/releases", draftRef).principal(publisher)
        .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, rootAssignment.nextEtag())
        .contentType("application/json").content("{\"commandRef\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    var releaseRef = UUID.fromString(json(frozen).path("release").path("releaseRef").asText());
    assertThat(fixture.memberRows.get(releaseRef)).extracting(UiLayoutReleaseMember::getContentRevisionId).contains(secondRef);
  }

  @Test void derivedPatchQuotaFailsBeforeImmutableWritesEvenWhenCandidateFits() throws Exception {
    var fixture = new Fixture(); var baseline = nativeDocument(child);
    var candidate = nativeDocument(child);
    for (int index = 0; index < 400; index++) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) baseline.path("config")).put("old-" + index + "x".repeat(500), "");
      ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("config")).put("new-" + index + "x".repeat(500), "");
    }
    fixture.nativeTableBaseline = baseline;
    assertThat(mapper.writeValueAsBytes(baseline).length).isLessThan(256 * 1024);
    assertThat(mapper.writeValueAsBytes(candidate).length).isLessThan(256 * 1024);
    var http = fixture.http(); var created = createFixtureDraft(http, "output-quota");
    int before = fixture.mutations.get();
    postCandidate(http, json(created).path("draft").path("draftRef").asText(), requiredEtag(created), candidateBody(child, candidate))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    assertThat(fixture.mutations.get()).isEqualTo(before);
    assertThat(fixture.definitionRows).isEmpty(); assertThat(fixture.revisionRows).isEmpty();
  }

  @Test void unmappedNativeFormatHasNoCompilerFallback() throws Exception {
    var fixture = new Fixture(); fixture.unmappedChild = true;
    var http = fixture.http(); var created = createFixtureDraft(http, "unmapped");
    int before = fixture.mutations.get();
    postCandidate(http, json(created).path("draft").path("draftRef").asText(), requiredEtag(created), candidateBody(child, nativeDocument(child)))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
    assertThat(fixture.mutations.get()).isEqualTo(before); assertThat(fixture.revisionRows).isEmpty();
  }

  @Test void formCandidatesCompileFiniteExpectationsIncludingArrayNulls() throws Exception {
    var corpus = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/ai/contracts/form-native-revision-conformance.v1.json")));
    for (var item : corpus.path("cases")) {
      var fixture = new Fixture(); fixture.nativeFormBaseline = item.path("baseline");
      var http = fixture.http(); var created = createFixtureDraft(http, "form-" + item.path("id").asText());
      var accepted = postCandidate(http, json(created).path("draft").path("draftRef").asText(), requiredEtag(created),
          candidateBody(root, item.path("command").path("authoringDocument")))
          .andExpect(status().isCreated()).andReturn();
      var ref = UUID.fromString(json(accepted).path("revision").path("revisionRef").asText());
      assertThat(mapper.readTree(fixture.revisionRows.get(ref).getPatchDocument())).isEqualTo(item.path("expectedPatch"));
      assertThat(json(accepted).path("nextDraft").path("targets").get(0).path("working").path("document"))
          .isEqualTo(item.path("command").path("authoringDocument"));
    }
  }

  private MvcResult createFixtureDraft(MockMvc http, String key) throws Exception {
    return http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
        .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", key))
        .andExpect(status().isCreated()).andReturn();
  }

  private com.fasterxml.jackson.databind.node.ObjectNode candidateBody(UiLayoutTarget target, JsonNode document) {
    var body = mapper.createObjectNode().put("commandRef", UUID.randomUUID().toString()).put("reason", "test-only raw authorship");
    body.set("target", mapper.valueToTree(target)); body.set("authoringDocument", document.deepCopy()); return body;
  }

  private org.springframework.test.web.servlet.ResultActions postCandidate(MockMvc http, String draftRef, String etag, JsonNode body) throws Exception {
    return http.perform(post("/api/praxis/config/ui-layouts/drafts/{id}/revisions", draftRef).principal(publisher)
        .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, etag)
        .contentType("application/json").content(mapper.writeValueAsBytes(body)));
  }

  @Test
  void lostCreationLocatorIsRecoveredWithoutMutationAndWorkspaceIsReadSeparately() throws Exception {
    var fixture = new Fixture(); var http = fixture.http();
    http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
        .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "lost-response-key"))
        .andExpect(status().isCreated());
    int mutationsBefore = fixture.mutations.get();
    var lookup = http.perform(get("/api/praxis/config/ui-layouts/drafts/by-creation-key").principal(publisher)
        .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "lost-response-key"))
        .andExpect(status().isOk()).andReturn();
    String locator = lookup.getResponse().getHeader(HttpHeaders.LOCATION);
    assertThat(locator).endsWith(json(lookup).path("draftRef").asText());
    http.perform(get(locator).principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT)).andExpect(status().isOk())
        .andExpect(jsonPath("$.targets").isArray());
    http.perform(get("/api/praxis/config/ui-layouts/drafts/by-creation-key").principal(() -> "other-actor")
        .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "lost-response-key"))
        .andExpect(status().isNotFound());
    assertThat(fixture.mutations.get()).isEqualTo(mutationsBefore);
    assertThat(fixture.draftRows).hasSize(1);
  }

  @Test
  void nativeTableProducerCommandsRoundTripAndDivergenceFailsBeforeWrites() throws Exception {
    JsonNode corpus = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(
        "docs/ai/contracts/table-native-revision-conformance.v1.json")));
    for (JsonNode item : corpus.path("cases")) {
      Fixture fixture = new Fixture(); fixture.nativeTableBaseline = item.path("baseline");
      MockMvc http = fixture.http();
      MvcResult created = http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
          .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
          .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "native-" + item.path("id").asText()))
          .andExpect(status().isCreated()).andReturn();
      String draftRef = json(created).path("draft").path("draftRef").asText();
      var malformed = (com.fasterxml.jackson.databind.node.ObjectNode) item.path("command").deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode)malformed.path("authoringDocument")).put("kind", "invalid-native");
      int before = fixture.mutations.get();
      http.perform(post("/api/praxis/config/ui-layouts/drafts/{id}/revisions", draftRef).principal(publisher)
          .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
          .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, requiredEtag(created))
          .contentType("application/json").content(mapper.writeValueAsBytes(malformed)))
          .andExpect(status().isUnprocessableEntity());
      assertThat(fixture.mutations.get()).isEqualTo(before);
      MvcResult accepted = http.perform(post("/api/praxis/config/ui-layouts/drafts/{id}/revisions", draftRef).principal(publisher)
          .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
          .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, requiredEtag(created))
          .contentType("application/json").content(mapper.writeValueAsBytes(item.path("command"))))
          .andExpect(status().isCreated()).andReturn();
      assertThat(json(accepted).path("nextDraft").path("targets").get(1).path("working").path("document"))
          .isEqualTo(item.path("command").path("authoringDocument"));
      assertThat(fixture.revisionRows).hasSize(1);
      assertThat(mapper.readTree(fixture.revisionRows.values().iterator().next().getPatchDocument())).isEqualTo(item.path("expectedPatch"));
    }
  }

  @Test
  void completeHttpLifecycleConsumesOnlyReturnedReferencesAndValidators() throws Exception {
    Fixture fixture = new Fixture();
    MockMvc http = fixture.http();

    ApprovedRelease first = createApprovedRelease(http, "first");
    MvcResult firstPublish = http.perform(post("/api/praxis/config/ui-layouts/release-head/publish")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_NONE_MATCH, "*")
            .contentType("application/json").content(headBody(first.releaseRef(), "publish first")))
        .andExpect(status().isOk()).andReturn();
    String firstHeadEtag = requiredEtag(firstPublish);
    assertThat(json(firstPublish).path("activeReleaseRef").asText()).isEqualTo(first.releaseRef());

    MvcResult headRead = http.perform(get("/api/praxis/config/ui-layouts/release-head")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isOk()).andReturn();
    assertThat(requiredEtag(headRead)).isEqualTo(firstHeadEtag);

    MvcResult withdrawn = http.perform(post("/api/praxis/config/ui-layouts/release-head/withdraw")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, firstHeadEtag)
            .contentType("application/json").content("{\"reason\":\"withdraw first\"}"))
        .andExpect(status().isOk()).andReturn();
    String withdrawnEtag = requiredEtag(withdrawn);
    assertThat(json(withdrawn).path("activeReleaseRef").isNull()).isTrue();

    MvcResult republished = http.perform(post("/api/praxis/config/ui-layouts/release-head/publish")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, withdrawnEtag)
            .contentType("application/json").content(headBody(first.releaseRef(), "republish first")))
        .andExpect(status().isOk()).andReturn();
    String republishedEtag = requiredEtag(republished);

    ApprovedRelease second = createApprovedRelease(http, "second");
    MvcResult secondPublish = http.perform(post("/api/praxis/config/ui-layouts/release-head/publish")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, republishedEtag)
            .contentType("application/json").content(headBody(second.releaseRef(), "publish second")))
        .andExpect(status().isOk()).andReturn();
    String secondHeadEtag = requiredEtag(secondPublish);

    MvcResult rolledBack = http.perform(post("/api/praxis/config/ui-layouts/release-head/rollback")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, secondHeadEtag)
            .contentType("application/json").content(headBody(first.releaseRef(), "rollback first")))
        .andExpect(status().isOk()).andReturn();
    assertThat(json(rolledBack).path("activeReleaseRef").asText()).isEqualTo(first.releaseRef());
    assertThat(requiredEtag(rolledBack)).isNotEqualTo(secondHeadEtag);

    int beforeDenied = fixture.mutations.get();
    http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(() -> "denied")
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "denied-create"))
        .andExpect(status().isForbidden());
    assertThat(fixture.mutations.get()).isEqualTo(beforeDenied);

    http.perform(post("/api/praxis/config/ui-layouts/release-head/withdraw")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, "W/" + requiredEtag(rolledBack))
            .contentType("application/json").content("{\"reason\":\"weak\"}"))
        .andExpect(status().isPreconditionFailed());
    http.perform(post("/api/praxis/config/ui-layouts/release-head/withdraw")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, "\"" + UUID.randomUUID() + "\"")
            .contentType("application/json").content("{\"reason\":\"stale strong validator\"}"))
        .andExpect(status().isPreconditionFailed());
    http.perform(post("/api/praxis/config/ui-layouts/release-head/withdraw")
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, requiredEtag(rolledBack))
            .contentType("application/json").content("{\"reason\":\"one\",\"reason\":\"duplicate\"}"))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/praxis/config/ui-layouts/releases/{releaseId}/review", UUID.randomUUID())
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isNotFound());

    int beforeInvalidBody = fixture.mutations.get();
    for (String invalidBody : new String[] {
        "{\"reason\":\"one\"} {}", "{\"reason\":\"one\"} INVALID",
        "{/*comment*/\"reason\":\"one\"}" }) {
      http.perform(post("/api/praxis/config/ui-layouts/release-head/withdraw")
              .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
              .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, requiredEtag(rolledBack))
              .contentType("application/json").content(invalidBody))
          .andExpect(status().isBadRequest());
    }
    assertThat(fixture.mutations.get()).isEqualTo(beforeInvalidBody);

    assertThat(fixture.configCommits.get()).isEqualTo(23);
    assertThat(fixture.operationalCommits.get()).isEqualTo(44);
    assertThat(fixture.configLocks.get()).isEqualTo(8);
    assertThat(fixture.configRollbacks.get()).isEqualTo(1);
    assertThat(fixture.operationalPreparedStatements.get()).isZero();
    assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
  }

  @Test
  void samePatchHashDoesNotMakeASupersededCommandObservableAsAccepted() throws Exception {
    MockMvc http = new Fixture().http();
    MvcResult created = http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "uncertain-create"))
        .andExpect(status().isCreated()).andReturn();
    String draftRef = json(created).path("draft").path("draftRef").asText();

    CreatedRevision first = revision(http, draftRef, requiredEtag(created), root, "same");
    CreatedRevision replacement = revision(http, draftRef, first.nextEtag(), root, "same");

    assertThat(replacement.contentHash()).isEqualTo(first.contentHash());
    assertThat(replacement.commandRef()).isNotEqualTo(first.commandRef());
    MvcResult reopened = http.perform(get("/api/praxis/config/ui-layouts/drafts/{draftId}", draftRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isOk()).andReturn();
    assertThat(json(reopened).path("targets").get(0).path("selectedRevision").path("acceptedCommandRef").asText())
        .isEqualTo(replacement.commandRef().toString());
  }

  @Test
  void repeatedCreateReturnsTheSameDraftWithoutRecapturingNativeBaselines() throws Exception {
    Fixture fixture = new Fixture();
    MockMvc http = fixture.http();
    var request = post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
        .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
        .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "lost-response-retry");

    MvcResult first = http.perform(request).andExpect(status().isCreated()).andReturn();
    MvcResult replay = http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "lost-response-retry"))
        .andExpect(status().isCreated()).andReturn();

    assertThat(json(replay).path("draft").path("draftRef").asText())
        .isEqualTo(json(first).path("draft").path("draftRef").asText());
    assertThat(fixture.workspaceCaptures).hasValue(1);
    assertThat(fixture.draftRows).hasSize(1);
  }

  @Test
  void malformedLifecycleBodiesFailSanitizedAndNoStoreBeforeContextOrPersistence() throws Exception {
    Fixture fixture = new Fixture();
    MockMvc http = fixture.http();
    UUID commandRef = UUID.randomUUID();
    String targetBody = target(root);
    List<String> malformed = List.of(
        "null",
        "{}",
        "{\"commandRef\":\"" + commandRef + "\",\"target\":" + targetBody
            + ",\"patchDocument\":{},\"reason\":\"edit\"}",
        "{\"commandRef\":\"" + commandRef + "\",\"target\":" + targetBody
            + ",\"authoringDocument\":null,\"patchDocument\":{},\"reason\":\"edit\"}",
        "{\"commandRef\":\"" + commandRef + "\",\"target\":" + targetBody
            + ",\"authoringDocument\":[],\"patchDocument\":{},\"reason\":\"edit\"}");

    for (String body : malformed) {
      http.perform(post("/api/praxis/config/ui-layouts/drafts/{draftId}/revisions", UUID.randomUUID())
              .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
              .header("X-Praxis-Context-Version", CONTEXT)
              .header(HttpHeaders.IF_MATCH, "\"" + UUID.randomUUID() + "\"")
              .contentType("application/json").content(body))
          .andExpect(status().isBadRequest())
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
          .andExpect(jsonPath("$.detail").value("The lifecycle request is malformed or contains unsupported fields."));
    }

    assertThat(fixture.workspaceCaptures).hasValue(0);
    assertThat(fixture.mutations).hasValue(0);
    assertThat(fixture.configCommits).hasValue(0);
    assertThat(fixture.operationalCommits).hasValue(0);
    assertThat(fixture.configLocks).hasValue(0);
  }

  @Test
  void inconsistentFreezeCommandAndReleaseStateFailClosedOnReopen() throws Exception {
    Fixture mutableFixture = new Fixture();
    MockMvc mutableHttp = mutableFixture.http();
    MvcResult mutableCreated = mutableHttp.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "inconsistent-draft"))
        .andExpect(status().isCreated()).andReturn();
    UUID mutableId = UUID.fromString(json(mutableCreated).path("draft").path("draftRef").asText());
    var malformed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(mutableFixture.draftRows.get(mutableId).getDraftDocument());
    malformed.put("freezeCommandRef", UUID.randomUUID().toString());
    mutableFixture.draftRows.get(mutableId).setDraftDocument(mapper.writeValueAsString(malformed));

    mutableHttp.perform(get("/api/praxis/config/ui-layouts/drafts/{draftId}", mutableId)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isConflict());

    Fixture releasedFixture = new Fixture();
    MockMvc releasedHttp = releasedFixture.http();
    MvcResult releasedCreated = releasedHttp.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "inconsistent-release"))
        .andExpect(status().isCreated()).andReturn();
    UUID releasedId = UUID.fromString(json(releasedCreated).path("draft").path("draftRef").asText());
    releasedFixture.draftRows.get(releasedId).setState("RELEASED");

    releasedHttp.perform(get("/api/praxis/config/ui-layouts/drafts/{draftId}", releasedId)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isConflict());
  }

  @Test
  void partialFreezeFailureRollsBackTheConfigTransactionBeforeDraftStateOrEnvelopeChanges() throws Exception {
    Fixture fixture = new Fixture();
    MockMvc http = fixture.http();
    MvcResult created = http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "partial-freeze"))
        .andExpect(status().isCreated()).andReturn();
    UUID draftId = UUID.fromString(json(created).path("draft").path("draftRef").asText());
    CreatedRevision rootRevision = revision(http, draftId.toString(), requiredEtag(created), root, "partial-root");
    CreatedRevision childRevision = revision(http, draftId.toString(), rootRevision.nextEtag(), child, "partial-child");
    CreatedAssignment rootAssignment = assignment(http, draftId.toString(), childRevision.nextEtag(), root, "partial-root");
    CreatedAssignment childAssignment = assignment(http, draftId.toString(), rootAssignment.nextEtag(), child, "partial-child");
    UiLayoutDraft before = fixture.draftRows.get(draftId);
    String documentBefore = before.getDraftDocument();
    UUID etagBefore = before.getDraftEtag();
    int rollbacksBefore = fixture.configRollbacks.get();
    org.mockito.Mockito.doThrow(new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "controlled review failure"))
        .when(fixture.reviews).save(any());

    http.perform(post("/api/praxis/config/ui-layouts/drafts/{draftId}/releases", draftId)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, childAssignment.nextEtag())
            .contentType("application/json").content("{\"commandRef\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isServiceUnavailable());

    assertThat(fixture.configRollbacks).hasValue(rollbacksBefore + 1);
    assertThat(before.getState()).isEqualTo("DRAFT");
    assertThat(before.getDraftEtag()).isEqualTo(etagBefore);
    assertThat(before.getDraftDocument()).isEqualTo(documentBefore);
  }

  private ApprovedRelease createApprovedRelease(MockMvc http, String marker) throws Exception {
    MvcResult created = http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "create-" + marker))
        .andExpect(status().isCreated()).andReturn();
    String draftRef = json(created).path("draft").path("draftRef").asText();
    assertThat(json(created).path("schemaVersion").asText()).isEqualTo("praxis.ui-layout-draft-workspace/v1");
    assertThat(json(created).path("targets")).hasSize(2);
    String draftEtag = requiredEtag(created);

    CreatedRevision rootRevision = revision(http, draftRef, draftEtag, root, marker + "-root");
    CreatedRevision childRevision = revision(http, draftRef, rootRevision.nextEtag(), child, marker + "-child");
    CreatedAssignment rootAssignment = assignment(http, draftRef, childRevision.nextEtag(), root, marker + "-root");
    CreatedAssignment childAssignment = assignment(http, draftRef, rootAssignment.nextEtag(), child, marker + "-child");

    MvcResult beforeFreeze = http.perform(get("/api/praxis/config/ui-layouts/drafts/{draftId}", draftRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isOk()).andReturn();
    JsonNode targetsBeforeFreeze = json(beforeFreeze).path("targets");
    UUID acceptedFreezeCommand = UUID.randomUUID();
    UUID differentCommand = UUID.randomUUID();
    String freezeBody = "{\"commandRef\":\"" + acceptedFreezeCommand + "\"}";
    MvcResult frozen = http.perform(post("/api/praxis/config/ui-layouts/drafts/{draftId}/releases", draftRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, childAssignment.nextEtag())
            .contentType("application/json").content(freezeBody))
        .andExpect(status().isCreated()).andReturn();
    String releaseRef = json(frozen).path("release").path("releaseRef").asText();
    assertThat(json(frozen).path("nextDraft").path("frozenReleaseRef").asText()).isEqualTo(releaseRef);
    assertThat(json(frozen).path("nextDraft").path("freezeCommandRef").asText())
        .isEqualTo(acceptedFreezeCommand.toString()).isNotEqualTo(differentCommand.toString());
    assertThat(json(frozen).path("nextDraft").path("targets")).isEqualTo(targetsBeforeFreeze);
    String frozenDraftEtag = json(frozen).path("nextDraft").path("draft").path("draftEtag").asText();
    MvcResult recoveredAfterLostResponse = http.perform(get("/api/praxis/config/ui-layouts/drafts/{draftId}", draftRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isOk()).andReturn();
    assertThat(json(recoveredAfterLostResponse).path("freezeCommandRef").asText())
        .isEqualTo(acceptedFreezeCommand.toString()).isNotEqualTo(differentCommand.toString());
    assertThat(json(recoveredAfterLostResponse).path("targets")).isEqualTo(targetsBeforeFreeze);
    MvcResult createReplayAfterFreeze = http.perform(post("/api/praxis/config/ui-layouts/drafts").principal(publisher)
            .param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header("Idempotency-Key", "create-" + marker))
        .andExpect(status().isCreated()).andReturn();
    assertThat(json(createReplayAfterFreeze).path("draft").path("draftRef").asText()).isEqualTo(draftRef);
    assertThat(json(createReplayAfterFreeze).path("frozenReleaseRef").asText()).isEqualTo(releaseRef);
    assertThat(json(createReplayAfterFreeze).path("freezeCommandRef").asText()).isEqualTo(acceptedFreezeCommand.toString());
    assertThat(json(createReplayAfterFreeze).path("draft").path("draftEtag").asText()).isEqualTo(frozenDraftEtag);
    String reviewEtag = requiredEtag(frozen);

    MvcResult reviewRead = http.perform(get("/api/praxis/config/ui-layouts/releases/{releaseId}/review", releaseRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isOk()).andReturn();
    assertThat(requiredEtag(reviewRead)).isEqualTo(reviewEtag);

    MvcResult submitted = http.perform(post("/api/praxis/config/ui-layouts/releases/{releaseId}/submit", releaseRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, reviewEtag))
        .andExpect(status().isOk()).andReturn();
    MvcResult workspaceAfterSubmit = http.perform(get("/api/praxis/config/ui-layouts/drafts/{draftId}", draftRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT))
        .andExpect(status().isOk()).andReturn();
    assertThat(json(workspaceAfterSubmit).path("freezeCommandRef").asText()).isEqualTo(acceptedFreezeCommand.toString());
    assertThat(json(workspaceAfterSubmit).path("draft").path("draftEtag").asText()).isEqualTo(frozenDraftEtag);
    String submittedEtag = requiredEtag(submitted);
    MvcResult approved = http.perform(post("/api/praxis/config/ui-layouts/releases/{releaseId}/approve", releaseRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, submittedEtag)
            .contentType("application/json").content("{\"reason\":\"approve " + marker + "\"}"))
        .andExpect(status().isOk()).andReturn();
    assertThat(json(approved).path("state").asText()).isEqualTo("APPROVED");
    return new ApprovedRelease(releaseRef, requiredEtag(approved));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode nativeDocument(UiLayoutTarget target) {
    var document = mapper.createObjectNode().put("kind", target.equals(child) ? "praxis.table.editor" : "praxis.dynamic-form.editor").put("version", 1);
    var config = document.putObject("config").put("title", target.componentId());
    if (target.equals(child)) { config.putArray("columns"); config.putObject("columnProjection").put("source", "schema"); }
    return document;
  }

  private CreatedRevision revision(MockMvc http, String draftRef, String etag, UiLayoutTarget target, String marker) throws Exception {
    UUID commandRef = UUID.randomUUID();
    var candidate = nativeDocument(target);
    ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("config")).put("title", marker);
    if (omitCandidateTitle) ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("config")).remove("title");
    String body = "{\"commandRef\":\"" + commandRef + "\",\"target\":" + target(target)
        + ",\"authoringDocument\":" + candidate + ",\"reason\":\"author " + marker + "\"}";
    MvcResult result = http.perform(post("/api/praxis/config/ui-layouts/drafts/{draftId}/revisions", draftRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, etag)
            .contentType("application/json").content(body))
        .andExpect(status().isCreated()).andReturn();
    int targetIndex = target.equals(root) ? 0 : 1;
    assertThat(json(result).path("nextDraft").path("targets").get(targetIndex)
        .path("selectedRevision").path("acceptedCommandRef").asText()).isEqualTo(commandRef.toString());
    return new CreatedRevision(json(result).path("revision").path("revisionRef").asText(), requiredEtag(result),
        json(result).path("revision").path("contentHash").asText(), commandRef);
  }

  private CreatedAssignment assignment(MockMvc http, String draftRef, String etag,
      UiLayoutTarget target, String marker) throws Exception {
    UUID commandRef = UUID.randomUUID();
    String body = "{\"commandRef\":\"" + commandRef + "\",\"target\":" + target(target)
        + ",\"layerClass\":\"TENANT\",\"selector\":{\"tenant\":\"tenant-a\"},\"priority\":10,\"contributionKey\":\""
        + marker + "\"}";
    MvcResult result = http.perform(post("/api/praxis/config/ui-layouts/drafts/{draftId}/assignments", draftRef)
            .principal(publisher).param("rootComponentType", ROOT_TYPE).param("rootComponentId", ROOT_ID)
            .header("X-Praxis-Context-Version", CONTEXT).header(HttpHeaders.IF_MATCH, etag)
            .contentType("application/json").content(body))
        .andExpect(status().isCreated()).andReturn();
    int targetIndex = target.equals(root) ? 0 : 1;
    assertThat(json(result).path("nextDraft").path("targets").get(targetIndex)
        .path("selectedAssignment").path("acceptedCommandRef").asText()).isEqualTo(commandRef.toString());
    return new CreatedAssignment(json(result).path("assignment").path("assignmentRevisionRef").asText(), requiredEtag(result));
  }

  private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()); }
  private String requiredEtag(MvcResult result) { String value = result.getResponse().getHeader(HttpHeaders.ETAG); assertThat(value).isNotBlank(); return value; }
  private String target(UiLayoutTarget value) { return "{\"componentType\":\"" + value.componentType() + "\",\"componentId\":\"" + value.componentId() + "\"}"; }
  private String headBody(String releaseRef, String reason) { return "{\"releaseId\":\"" + releaseRef + "\",\"reason\":\"" + reason + "\"}"; }
  private record CreatedRevision(String revisionRef, String nextEtag, String contentHash, UUID commandRef) {}
  private record CreatedAssignment(String assignmentRef, String nextEtag) {}
  private record ApprovedRelease(String releaseRef, String reviewEtag) {}

  private final class Fixture {
    JsonNode nativeTableBaseline;
    JsonNode nativeFormBaseline;
    boolean unmappedChild;
    final DataSource configDataSource = mock(DataSource.class);
    final DataSource operationalDataSource = mock(DataSource.class);
    final AtomicInteger configCommits = new AtomicInteger();
    final AtomicInteger configRollbacks = new AtomicInteger();
    final AtomicInteger operationalCommits = new AtomicInteger();
    final AtomicInteger configLocks = new AtomicInteger();
    final AtomicInteger operationalPreparedStatements = new AtomicInteger();
    final AtomicInteger mutations = new AtomicInteger();
    final AtomicInteger workspaceCaptures = new AtomicInteger();
    final Map<UUID, UiLayoutDraft> draftRows = new LinkedHashMap<>();
    final Map<UUID, UiLayoutDefinition> definitionRows = new LinkedHashMap<>();
    final Map<UUID, UiLayoutRevision> revisionRows = new LinkedHashMap<>();
    final Map<UUID, UiLayoutAssignmentRevision> assignmentRows = new LinkedHashMap<>();
    final Map<UUID, UiLayoutRelease> releaseRows = new LinkedHashMap<>();
    final Map<UUID, List<UiLayoutReleaseMember>> memberRows = new LinkedHashMap<>();
    final Map<UUID, UiLayoutReleaseReview> reviewRows = new LinkedHashMap<>();
    final Map<String, UiLayoutReleaseHead> headRows = new LinkedHashMap<>();
    final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
    final UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
    final UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
    final UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
    final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
    final UiLayoutReleaseMemberRepository members = mock(UiLayoutReleaseMemberRepository.class);
    final UiLayoutReleaseReviewRepository reviews = mock(UiLayoutReleaseReviewRepository.class);
    final UiLayoutReleaseApprovalRepository approvals = mock(UiLayoutReleaseApprovalRepository.class);
    final UiLayoutReleaseHeadRepository heads = mock(UiLayoutReleaseHeadRepository.class);
    final UiLayoutReleaseEventRepository events = mock(UiLayoutReleaseEventRepository.class);

    Fixture() throws Exception {
      when(configDataSource.getConnection()).thenAnswer(call -> connection(configDataSource, configCommits, configRollbacks, true));
      when(operationalDataSource.getConnection()).thenAnswer(call -> connection(operationalDataSource, operationalCommits, new AtomicInteger(), false));
      stores();
    }

    MockMvc http() {
      TransactionTemplate operational = new TransactionTemplate(new DataSourceTransactionManager(operationalDataSource));
      UiLayoutLifecycleInvocationProvider provider = (principal, context, requestedRoot) -> {
        // Final revalidation reads current claims without nesting an operational transaction into Config writes.
        if (TransactionSynchronizationManager.hasResource(configDataSource)) return new UiLayoutLifecycleInvocation(
            principal.getName(), "tenant-a", "procurement", "lab", CONTEXT, new UiLayoutCompositionRegistration(root, List.of(root, child)));
        return operational.execute(status -> {
        assertThat(TransactionSynchronizationManager.hasResource(operationalDataSource)).isTrue();
        assertThat(TransactionSynchronizationManager.hasResource(configDataSource)).isFalse();
        return new UiLayoutLifecycleInvocation(principal.getName(), "tenant-a", "procurement", "lab", CONTEXT,
            new UiLayoutCompositionRegistration(root, List.of(root, child)));
        });
      };
      UiLayoutLifecycleAdmission admission = (operation, invocation) -> {
        if ("denied".equals(invocation.actorRef())) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "denied");
      };
      UiLayoutLifecycleStructureValidator structure = new UiLayoutLifecycleStructureValidator() {
        @Override public void validateTarget(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, org.praxisplatform.config.service.UiLayoutValidationContext validation) { assertThat(invocation.composition().targets()).contains(target); assertConfigWhenActive(); }
        @Override public void validateAuthoringBaseline(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor, JsonNode baselineDocument, org.praxisplatform.config.service.UiLayoutValidationContext validation) { assertThat(descriptor.documentType()).isNotBlank(); assertThat(baselineDocument.isObject()).isTrue(); }
        @Override public void validateAuthoringRevision(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor baselineDescriptor, JsonNode baselineDocument, UiLayoutAuthoringDocumentDescriptor candidateDescriptor, JsonNode candidateDocument, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patchDocument, org.praxisplatform.config.service.UiLayoutValidationContext validation) { assertThat(candidateDescriptor).isEqualTo(baselineDescriptor); assertThat(baselineDocument.isObject()).isTrue(); assertThat(candidateDocument.isObject()).isTrue(); assertThat(patchDescriptor.schemaVersion()).isEqualTo("praxis.ui-layout/v1"); }
        @Override public void validatePatch(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, JsonNode patch, org.praxisplatform.config.service.UiLayoutValidationContext validation) { assertThat(patch.isObject()).isTrue(); }
        @Override public void validateAssignment(UiLayoutLifecycleInvocation invocation, UiLayoutTarget target, UiLayoutResolutionCandidate.LayerClass layer, UiLayoutAudienceSelector selector, org.praxisplatform.config.service.UiLayoutValidationContext validation) { assertThat(selector.tenant()).isEqualTo("tenant-a"); }
        @Override public void validateReleaseTargets(UiLayoutLifecycleInvocation invocation, List<UiLayoutTarget> targets, org.praxisplatform.config.service.UiLayoutValidationContext validation) { assertThat(targets).containsExactly(root, child); }
      };
      JdbcUiLayoutRevisionAllocationLock lock = new JdbcUiLayoutRevisionAllocationLock(
          new NamedParameterJdbcTemplate(new JdbcTemplate(configDataSource)));
      JdbcUiLayoutDraftCreationLock creationLock = new JdbcUiLayoutDraftCreationLock(
          new NamedParameterJdbcTemplate(new JdbcTemplate(configDataSource)));
      UiLayoutDraftWorkspaceSource workspaceSource = (invocation, validation) -> {
        workspaceCaptures.incrementAndGet();
        return new UiLayoutDraftWorkspaceSeed(List.of(
            targetSeed(root, "praxis.dynamic-page.authoring-document", "page-authoring/v2", "root-source", invocation),
            targetSeed(child, "praxis.table.authoring-document", "table-authoring/v3", "child-source", invocation)));
      };
      UiLayoutLifecycleCommandService commands = UiLayoutLifecycleCommandService.assemble(definitions, revisions, assignments, drafts,
          releases, members, reviews, approvals, heads, events, lock, new CanonicalJsonHashService(mapper), mapper,
          (release, frozen) -> assertThat(frozen).hasSize(2), provider, admission, structure,
          workspaceSource, creationLock, new UiLayoutMetadataTestFixtures.MemoryStore(),
          (invocation, target, authoring, source, baseline, metadata) -> assertConfigWhenActive(),
          new TransactionTemplate(new DataSourceTransactionManager(configDataSource)), (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
      UiLayoutLifecycleReadService reads = new UiLayoutLifecycleReadService(provider, admission, drafts, releases, reviews, heads,
          definitions, revisions, assignments, mapper, new CanonicalJsonHashService(mapper), structure, (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));
      UiLayoutLifecycleController controller = new UiLayoutLifecycleController(reads, commands, new UiLayoutLifecycleRequestDecoder(mapper));
      return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new UiLayoutResolutionExceptionHandler(), new org.praxisplatform.config.controller.UiLayoutRevisionRequestBodyAdvice()).build();
    }

    private void stores() {
      when(drafts.save(any())).thenAnswer(call -> put(draftRows, (UiLayoutDraft) call.getArgument(0)));
      when(drafts.findForUpdateById(any())).thenAnswer(call -> Optional.ofNullable(draftRows.get(call.getArgument(0))));
      when(drafts.findByIdAndTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(any(), anyString(), anyString(), anyString(), anyString()))
          .thenAnswer(call -> scopedDraft(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
      when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
          anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> draftRows.values().stream()
              .filter(v -> v.getTenantId().equals(call.getArgument(0)) && v.getEnvironment().equals(call.getArgument(1))
                  && v.getRootComponentType().equals(call.getArgument(2)) && v.getRootComponentId().equals(call.getArgument(3))
                  && v.getCreatedBy().equals(call.getArgument(4)) && v.getCreationIdempotencyKey().equals(call.getArgument(5))).findFirst());
      when(definitions.save(any())).thenAnswer(call -> put(definitionRows, (UiLayoutDefinition) call.getArgument(0)));
      when(definitions.findById(any())).thenAnswer(call -> Optional.ofNullable(definitionRows.get(call.getArgument(0))));
      when(definitions.findForUpdateByTarget(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> definitionRows.values().stream()
          .filter(v -> v.getTenantId().equals(call.getArgument(0)) && v.getEnvironment().equals(call.getArgument(1))
              && v.getComponentType().equals(call.getArgument(2)) && v.getComponentId().equals(call.getArgument(3))).findFirst());
      when(revisions.save(any())).thenAnswer(call -> put(revisionRows, (UiLayoutRevision) call.getArgument(0)));
      when(revisions.findById(any())).thenAnswer(call -> Optional.ofNullable(revisionRows.get(call.getArgument(0))));
      when(revisions.findMaximumRevisionNumber(any())).thenAnswer(call -> revisionRows.values().stream().filter(v -> v.getDefinitionId().equals(call.getArgument(0)))
          .map(UiLayoutRevision::getRevisionNumber).max(Comparator.naturalOrder()).orElse(null));
      when(assignments.save(any())).thenAnswer(call -> put(assignmentRows, (UiLayoutAssignmentRevision) call.getArgument(0)));
      when(assignments.findById(any())).thenAnswer(call -> Optional.ofNullable(assignmentRows.get(call.getArgument(0))));
      when(releases.save(any())).thenAnswer(call -> put(releaseRows, (UiLayoutRelease) call.getArgument(0)));
      when(releases.findByIdAndTenantIdAndEnvironment(any(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(releaseRows.get(call.getArgument(0)))
          .filter(v -> v.getTenantId().equals(call.getArgument(1)) && v.getEnvironment().equals(call.getArgument(2))));
      when(releases.findBySourceDraftId(any())).thenAnswer(call -> releaseRows.values().stream()
          .filter(value -> call.getArgument(0).equals(value.getSourceDraftId())).findFirst());
      when(members.saveAll(any())).thenAnswer(call -> {
        List<UiLayoutReleaseMember> saved = new ArrayList<>((List<UiLayoutReleaseMember>) call.getArgument(0));
        memberRows.put(saved.getFirst().getReleaseId(), saved); mutations.incrementAndGet(); return saved;
      });
      when(members.findByReleaseIdOrderByMemberOrder(any())).thenAnswer(call -> memberRows.getOrDefault(call.getArgument(0), List.of()));
      when(reviews.save(any())).thenAnswer(call -> put(reviewRows, (UiLayoutReleaseReview) call.getArgument(0)));
      when(reviews.findByReleaseId(any())).thenAnswer(call -> reviewRows.values().stream().filter(v -> v.getReleaseId().equals(call.getArgument(0))).findFirst());
      when(reviews.findForUpdateByReleaseId(any())).thenAnswer(call -> reviewRows.values().stream().filter(v -> v.getReleaseId().equals(call.getArgument(0))).findFirst());
      when(approvals.save(any())).thenAnswer(call -> { mutations.incrementAndGet(); return (UiLayoutReleaseApproval) call.getArgument(0); });
      when(events.save(any())).thenAnswer(call -> { mutations.incrementAndGet(); return (UiLayoutReleaseEvent) call.getArgument(0); });
      when(heads.save(any())).thenAnswer(call -> putHead((UiLayoutReleaseHead) call.getArgument(0)));
      when(heads.saveAndFlush(any())).thenAnswer(call -> putHead((UiLayoutReleaseHead) call.getArgument(0)));
      when(heads.findForUpdate(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(headRows.get(headKey(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)))));
      when(heads.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentId(anyString(), anyString(), anyString(), anyString()))
          .thenAnswer(call -> Optional.ofNullable(headRows.get(headKey(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)))));
    }

    private <T> T put(Map<UUID, T> rows, T value) {
      assertConfigWhenActive(); UUID id;
      if (value instanceof UiLayoutDraft v) id = v.getId(); else if (value instanceof UiLayoutDefinition v) id = v.getId();
      else if (value instanceof UiLayoutRevision v) id = v.getId(); else if (value instanceof UiLayoutAssignmentRevision v) id = v.getId();
      else if (value instanceof UiLayoutRelease v) id = v.getId(); else if (value instanceof UiLayoutReleaseReview v) id = v.getId();
      else throw new IllegalArgumentException("unsupported row");
      rows.put(id, value); mutations.incrementAndGet(); return value;
    }
    private UiLayoutReleaseHead putHead(UiLayoutReleaseHead value) { assertConfigWhenActive(); headRows.put(headKey(value.getTenantId(), value.getEnvironment(), value.getRootComponentType(), value.getRootComponentId()), value); mutations.incrementAndGet(); return value; }
    private Optional<UiLayoutDraft> scopedDraft(UUID id, String tenant, String environment, String type, String componentId) { return Optional.ofNullable(draftRows.get(id)).filter(v -> tenant.equals(v.getTenantId()) && environment.equals(v.getEnvironment()) && type.equals(v.getRootComponentType()) && componentId.equals(v.getRootComponentId())); }
    private UiLayoutDraftWorkspaceSeed.TargetSeed targetSeed(UiLayoutTarget target, String type, String authoringVersion, String sourceRef,
        UiLayoutLifecycleInvocation invocation) {
      if (nativeFormBaseline != null && target.equals(root)) return new UiLayoutDraftWorkspaceSeed.TargetSeed(target,
          new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "fixture.form", "1"),
          new UiLayoutPatchDocumentDescriptor("fixture.patch", "praxis.ui-layout/v1"), sourceRef, nativeFormBaseline.deepCopy(),
          UiLayoutMetadataTestFixtures.metadata(invocation.actorRef(), invocation.administrativeUnit(), invocation.contextVersion()));
      if (unmappedChild && target.equals(child)) return new UiLayoutDraftWorkspaceSeed.TargetSeed(target,
          new UiLayoutAuthoringDocumentDescriptor("unsupported-native", "fixture.unsupported", "1"),
          new UiLayoutPatchDocumentDescriptor("fixture.patch", "praxis.ui-layout/v1"), sourceRef, nativeDocument(target),
          UiLayoutMetadataTestFixtures.metadata(invocation.actorRef(), invocation.administrativeUnit(), invocation.contextVersion()));
      if (nativeTableBaseline != null && target.equals(child)) return new UiLayoutDraftWorkspaceSeed.TargetSeed(target,
          new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "fixture.table", "1"),
          new UiLayoutPatchDocumentDescriptor("fixture.patch", "praxis.ui-layout/v1"), sourceRef, nativeTableBaseline.deepCopy(),
          UiLayoutMetadataTestFixtures.metadata(invocation.actorRef(), invocation.administrativeUnit(), invocation.contextVersion()));
      return new UiLayoutDraftWorkspaceSeed.TargetSeed(target,
          new UiLayoutAuthoringDocumentDescriptor(target.equals(child) ? "praxis.table.editor" : "praxis.dynamic-form.editor", "urn:" + type, "1"),
          new UiLayoutPatchDocumentDescriptor("urn:praxis:ui-layout-patch", "praxis.ui-layout/v1"), sourceRef,
          nativeDocument(target),
          UiLayoutMetadataTestFixtures.metadata(invocation.actorRef(), invocation.administrativeUnit(), invocation.contextVersion()));
    }
    private String headKey(String tenant, String environment, String type, String id) { return tenant + "|" + environment + "|" + type + "|" + id; }
    private void assertConfigWhenActive() { if (TransactionSynchronizationManager.isActualTransactionActive()) { assertThat(TransactionSynchronizationManager.hasResource(configDataSource)).isTrue(); assertThat(TransactionSynchronizationManager.hasResource(operationalDataSource)).isFalse(); } }

    private Connection connection(DataSource owner, AtomicInteger commits, AtomicInteger rollbacks, boolean config) throws Exception {
      Connection connection = mock(Connection.class); AtomicBoolean autoCommit = new AtomicBoolean(true);
      when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
      org.mockito.Mockito.doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
      org.mockito.Mockito.doAnswer(call -> { commits.incrementAndGet(); return null; }).when(connection).commit();
      org.mockito.Mockito.doAnswer(call -> { rollbacks.incrementAndGet(); return null; }).when(connection).rollback();
      if (config) when(connection.prepareStatement(anyString())).thenAnswer(call -> {
        assertThat(TransactionSynchronizationManager.hasResource(owner)).isTrue(); configLocks.incrementAndGet();
        PreparedStatement statement = mock(PreparedStatement.class); ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false); when(statement.executeQuery()).thenReturn(rows); return statement;
      });
      else when(connection.prepareStatement(anyString())).thenAnswer(call -> {
        operationalPreparedStatements.incrementAndGet();
        throw new AssertionError("Lifecycle Config writes must not use the operational connection.");
      });
      return connection;
    }
  }
}
