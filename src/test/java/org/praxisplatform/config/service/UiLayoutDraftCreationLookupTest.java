package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.*;
import org.praxisplatform.config.domain.UiLayoutDraft;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.repository.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class UiLayoutDraftCreationLookupTest {
  final Principal principal = () -> "actor";
  final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "orders");
  final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("actor", "tenant", "unit", "test", "ctx",
      new UiLayoutCompositionRegistration(root, List.of(root)));
  final UiLayoutLifecycleInvocationProvider provider = mock(UiLayoutLifecycleInvocationProvider.class);
  final UiLayoutLifecycleAdmission admission = mock(UiLayoutLifecycleAdmission.class);
  final UiLayoutDraftRepository drafts = mock(UiLayoutDraftRepository.class);
  final UiLayoutReleaseRepository releases = mock(UiLayoutReleaseRepository.class);
  final UiLayoutReleaseReviewRepository reviews = mock(UiLayoutReleaseReviewRepository.class);
  final UiLayoutReleaseHeadRepository heads = mock(UiLayoutReleaseHeadRepository.class);
  final UiLayoutDefinitionRepository definitions = mock(UiLayoutDefinitionRepository.class);
  final UiLayoutRevisionRepository revisions = mock(UiLayoutRevisionRepository.class);
  final UiLayoutAssignmentRevisionRepository assignments = mock(UiLayoutAssignmentRevisionRepository.class);
  final UiLayoutLifecycleStructureValidator structure = mock(UiLayoutLifecycleStructureValidator.class);
  final UiLayoutLifecycleCommandService commands = mock(UiLayoutLifecycleCommandService.class);
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final UiLayoutLifecycleReadService reads = new UiLayoutLifecycleReadService(provider, admission, drafts, releases, reviews,
      heads, definitions, revisions, assignments, mapper, new CanonicalJsonHashService(mapper), structure, (budgetOperation, budgetInvocation) -> java.time.Duration.ofMinutes(1));

  UiLayoutDraftCreationLookupTest() {
    when(provider.resolve(principal, "ctx", root)).thenReturn(invocation);
  }
  UiLayoutDraft row() {
    var row = new UiLayoutDraft(); row.setId(UUID.randomUUID()); row.setDraftEtag(UUID.randomUUID());
    row.setRootComponentType(root.componentType()); row.setRootComponentId(root.componentId());
    row.setCreatedAt(Instant.EPOCH); row.setUpdatedAt(Instant.EPOCH.plusSeconds(60)); row.setState("RELEASED");
    row.setDraftDocument("private-invalid-workspace-must-not-be-decoded");
    return row;
  }
  void found(UiLayoutDraft row) {
    when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant", "test", "praxis-table", "orders", "actor", "Key")).thenReturn(Optional.of(row));
  }
  void assertFailure(Runnable action, UiLayoutLifecycleException.Code code) {
    assertThatThrownBy(action::run).isInstanceOf(UiLayoutLifecycleException.class)
        .satisfies(error -> {
          assertThat(((UiLayoutLifecycleException) error).getCode()).isEqualTo(code);
          assertThat(error.getCause()).isNull(); assertThat(error.getMessage()).doesNotContain("private", "Key");
        });
  }
  void noOtherSources() { verifyNoInteractions(releases, reviews, heads, definitions, revisions, assignments, structure, commands); }

  @Test void returnsCurrentMinimalReceiptWithoutWorkspaceOrWrites() {
    var row = row(); found(row);
    var result = reads.draftByCreationKey(principal, " ctx ", root, " Key ");
    assertThat(result.draftRef()).isEqualTo(row.getId().toString());
    assertThat(result.state()).isEqualTo("RELEASED"); assertThat(result.updatedAt()).isEqualTo(row.getUpdatedAt());
    verify(admission, times(3)).require(UiLayoutLifecycleOperation.READ_DRAFT, invocation);
    verify(drafts).findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant", "test", "praxis-table", "orders", "actor", "Key");
    verifyNoMoreInteractions(drafts, admission); noOtherSources();
  }
  @Test void missingOrDifferentCaseDoesNotSearchGloballyOrCreate() {
    found(row());
    assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, "key"), UiLayoutLifecycleException.Code.NOT_FOUND);
    verify(drafts).findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant", "test", "praxis-table", "orders", "actor", "key");
    verifyNoMoreInteractions(drafts); noOtherSources();
  }
  @Test void deniesBeforeRepositoryAndNeverRequiresCreate() {
    doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private Key"))
        .when(admission).require(UiLayoutLifecycleOperation.READ_DRAFT, invocation);
    assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, "Key"), UiLayoutLifecycleException.Code.DENIED);
    verifyNoInteractions(drafts); noOtherSources();
  }
  @Test void rejectsInvalidKeysWithoutEchoOrDispatch() {
    for (String key : new String[] {null, "  ", "\u2003", "private".repeat(31)}) {
      assertThatThrownBy(() -> reads.draftByCreationKey(principal, "ctx", root, key))
          .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
    }
    verifyNoInteractions(provider, admission, drafts); noOtherSources();
  }
  @Test void acceptsMaximumTrimmedKeyLength() {
    String key = "a".repeat(180);
    assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, " " + key + " "), UiLayoutLifecycleException.Code.NOT_FOUND);
    verify(drafts).findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant", "test", "praxis-table", "orders", "actor", key);
  }
  @Test void rejectsAnonymousBeforeRepository() {
    assertFailure(() -> reads.draftByCreationKey(null, "ctx", root, "Key"), UiLayoutLifecycleException.Code.SESSION_REQUIRED);
    verifyNoInteractions(provider, admission, drafts);
  }
  @Test void rechecksDenialAfterObservation() {
    found(row()); doNothing().doThrow(new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private Key"))
        .when(admission).require(UiLayoutLifecycleOperation.READ_DRAFT, invocation);
    assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, "Key"), UiLayoutLifecycleException.Code.DENIED);
  }
  @Test void fullInvocationDriftRejectsEvenWhenContextVersionStaysEqual() {
    found(row());
    for (var changed : List.of(
        new UiLayoutLifecycleInvocation("other", "tenant", "unit", "test", "ctx", invocation.composition()),
        new UiLayoutLifecycleInvocation("actor", "other", "unit", "test", "ctx", invocation.composition()),
        new UiLayoutLifecycleInvocation("actor", "tenant", "other", "test", "ctx", invocation.composition()),
        new UiLayoutLifecycleInvocation("actor", "tenant", "unit", "other", "ctx", invocation.composition()),
        new UiLayoutLifecycleInvocation("actor", "tenant", "unit", "test", "ctx", new UiLayoutCompositionRegistration(root,
            List.of(root, new UiLayoutTarget("praxis-form", "child")))))) {
      when(provider.resolve(principal, "ctx", root)).thenReturn(invocation, changed);
      assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, "Key"), UiLayoutLifecycleException.Code.CONTEXT_STALE);
    }
  }
  @Test void repositoryFailuresAreSanitizedTechnicalFailures() {
    when(drafts.findByTenantIdAndEnvironmentAndRootComponentTypeAndRootComponentIdAndCreatedByAndCreationIdempotencyKey(
        "tenant", "test", "praxis-table", "orders", "actor", "Key")).thenThrow(new IllegalStateException("private Key SQL"));
    assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, "Key"), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
  }
  @Test void providerAndAdmissionFailuresAreTechnicalNotPolicyDenials() {
    when(provider.resolve(principal, "ctx", root)).thenThrow(new IllegalStateException("private Key"));
    assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, "Key"), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    doReturn(invocation).when(provider).resolve(principal, "ctx", root);
    doThrow(new IllegalStateException("private Key")).when(admission).require(UiLayoutLifecycleOperation.READ_DRAFT, invocation);
    assertFailure(() -> reads.draftByCreationKey(principal, "ctx", root, "Key"), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
    verifyNoInteractions(drafts);
  }
  MockMvc http() {
    return MockMvcBuilders.standaloneSetup(new UiLayoutLifecycleController(reads, commands, new UiLayoutLifecycleRequestDecoder(mapper)))
        .setControllerAdvice(new UiLayoutResolutionExceptionHandler()).build();
  }
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
    return get("/api/praxis/config/ui-layouts/drafts/by-creation-key").principal(principal)
        .param("rootComponentType", root.componentType()).param("rootComponentId", root.componentId())
        .header("X-Praxis-Context-Version", "ctx").header("Idempotency-Key", "Key");
  }
  @Test void staticRouteReturnsIdentityNoStoreAndNever304() throws Exception {
    var row = row();
    // Use the same actual row/validator for conditional-header assertions.
    found(row);
    var result = http().perform(request().header("If-None-Match", "\"" + row.getDraftEtag() + "\"").header("If-Match", "*"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("ETag", "\"" + row.getDraftEtag() + "\""))
        .andExpect(header().string("Location", "/api/praxis/config/ui-layouts/drafts/" + row.getId()))
        .andExpect(jsonPath("$.draftRef").value(row.getId().toString())).andExpect(jsonPath("$.targets").doesNotExist())
        .andExpect(jsonPath("$.creationIdempotencyKey").doesNotExist()).andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("private", "Key", "tenant", "actor"); noOtherSources();
  }
  @Test void missingAssociationIsSanitized404WithNoStore() throws Exception {
    http().perform(request()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(header().string("Cache-Control", "no-store")); noOtherSources();
  }
  @Test void requiredHeadersAndRootAreRejectedBeforeRead() throws Exception {
    var http = http();
    http.perform(get("/api/praxis/config/ui-layouts/drafts/by-creation-key").principal(principal))
        .andExpect(status().isBadRequest());
    // Independently omit the key rather than changing policy or supplying a UUID route.
    http.perform(get("/api/praxis/config/ui-layouts/drafts/by-creation-key").principal(principal)
        .param("rootComponentType", root.componentType()).param("rootComponentId", root.componentId())
        .header("X-Praxis-Context-Version", "ctx")).andExpect(status().isBadRequest());
    verifyNoInteractions(provider, admission, drafts);
  }
}
