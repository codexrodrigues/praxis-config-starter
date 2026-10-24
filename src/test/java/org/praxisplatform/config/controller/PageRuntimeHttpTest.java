package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.service.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class PageRuntimeHttpTest {
  final PageRuntimeReadService pages = mock(PageRuntimeReadService.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
  final EnterpriseRuntimeNavigationProvider navigation = mock(EnterpriseRuntimeNavigationProvider.class);
  final String etag = "a".repeat(64);
  final UUID pageId = UUID.randomUUID();
  MockMvc mvc;

  @BeforeEach void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new PageRuntimeController(pages),
        new EnterpriseRuntimeContextController(mock(EnterpriseRuntimeContextProvider.class),
            mock(EnterpriseRuntimeContextSwitchProvider.class), mock(EnterpriseRuntimeContextChoiceProvider.class),
            mock(EnterpriseRuntimeTenantProvider.class), navigation, mock(EnterpriseRuntimeSecurityEventProvider.class)))
        .setControllerAdvice(new UiLayoutResolutionExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules().addMixIn(org.springframework.http.ProblemDetail.class, org.springframework.http.converter.json.ProblemDetailJacksonMixin.class))).build();
  }

  @Test void timestampIsNotStrongIdentityAndCurrentAuthorityAlwaysPrecedes304() throws Exception {
    when(pages.catalog(any(), eq("c1"), isNull(), eq(50))).thenReturn(
        new PageCatalogResponse(PageCatalogResponse.SCHEMA_VERSION, List.of(), "c1", etag, null, Instant.EPOCH),
        new PageCatalogResponse(PageCatalogResponse.SCHEMA_VERSION, List.of(), "c1", etag, null, Instant.EPOCH.plusSeconds(5)));
    mvc.perform(get("/api/praxis/config/pages").principal(() -> "actor").header("X-Praxis-Context-Version", "c1"))
        .andExpect(status().isOk()).andExpect(header().string("ETag", "W/\"" + etag + "\""))
        .andExpect(header().string("Cache-Control", "no-cache, private"));
    mvc.perform(get("/api/praxis/config/pages").principal(() -> "actor").header("X-Praxis-Context-Version", "c1")
        .header("If-None-Match", "\"other\", \"" + etag + "\""))
        .andExpect(status().isNotModified()).andExpect(content().string(""));
    when(pages.catalog(any(), eq("c1"), isNull(), eq(50))).thenThrow(
        new UiLayoutResolutionException(UiLayoutResolutionException.Code.PUBLICATION_SNAPSHOT_CHANGED, "private secret", null));
    mvc.perform(get("/api/praxis/config/pages").principal(() -> "actor").header("X-Praxis-Context-Version", "c1")
        .header("If-None-Match", "W/\"" + etag + "\""))
        .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private secret"))));
    verify(pages, times(3)).catalog(any(), eq("c1"), isNull(), eq(50));
  }

  @Test void publishedCopySourceSerializesAsTheExistingExactCreationSelector() throws Exception {
    UUID releaseId = UUID.randomUUID(), revisionId = UUID.randomUUID();
    var source = new PageSourceReference.Published(pageId, releaseId, revisionId);
    var head = new UiLayoutReleaseHeadReceipt(new UiLayoutTarget("praxis-dynamic-page", pageId.toString()),
        releaseId.toString(), UUID.randomUUID().toString(), Instant.EPOCH);
    when(pages.published(any(), eq("c1"), eq(pageId))).thenReturn(new PublishedPageResponse(
        PublishedPageResponse.SCHEMA_VERSION, pageId, "/pages/" + pageId,
        new ObjectMapper().createObjectNode(), "c1", etag, head, source, Instant.EPOCH));

    var response = mvc.perform(get("/api/praxis/config/pages/" + pageId + "/published")
        .principal(() -> "actor").header("X-Praxis-Context-Version", "c1").header("If-None-Match", "\"old-body\""))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "W/\"" + etag + "\""))
        .andExpect(jsonPath("$.copySource.kind").value("published-page"))
        .andExpect(jsonPath("$.copySource.pageId").value(pageId.toString()))
        .andExpect(jsonPath("$.copySource.releaseId").value(releaseId.toString()))
        .andExpect(jsonPath("$.copySource.revisionId").value(revisionId.toString()))
        .andReturn();
    var mapper = new ObjectMapper();
    var wireSource = mapper.readTree(response.getResponse().getContentAsString()).get("copySource");
    assertThat(mapper.treeToValue(wireSource, PageSourceReference.class)).isEqualTo(source);
  }

  @Test void conditionalReadsUseCanonicalWeakParsingAfterOwnerResolution() throws Exception {
    when(pages.catalog(any(), eq("c1"), isNull(), eq(50))).thenReturn(
        new PageCatalogResponse(PageCatalogResponse.SCHEMA_VERSION, List.of(), "c1", etag, null, Instant.EPOCH));
    for (String condition : List.of("*", "W/\"" + etag + "\"", "\"other\", W/\"" + etag + "\"")) {
      mvc.perform(get("/api/praxis/config/pages").principal(() -> "actor")
          .header("X-Praxis-Context-Version", "c1").header("If-None-Match", condition))
          .andExpect(status().isNotModified()).andExpect(header().string("ETag", "W/\"" + etag + "\""));
    }
    for (String condition : List.of("*, \"private\"", "private", "\"unterminated")) {
      mvc.perform(get("/api/praxis/config/pages").principal(() -> "actor")
          .header("X-Praxis-Context-Version", "c1").header("If-None-Match", condition))
          .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
    verify(pages, times(6)).catalog(any(), eq("c1"), isNull(), eq(50));
    assertThat(org.praxisplatform.config.http.HttpEntityTagCondition.parse("W/\"" + etag + "\"").matchesStrong(etag)).isFalse();
  }

  @Test void navigationMapsOwnerFailuresAndNeverReturnsPrivateErrorDetails() throws Exception {
    for (var code : List.of(UiLayoutLifecycleException.Code.NOT_FOUND, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE)) {
      doThrow(new UiLayoutLifecycleException(code, "private ledger"))
          .when(navigation).getNavigation(any(), anyString(), any(), any(), anyInt());
      mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor")
          .header("X-Praxis-Context-Version", "c1").param("parentRef", pageId.toString()).header("If-None-Match", "*"))
          .andExpect(status().is(code == UiLayoutLifecycleException.Code.NOT_FOUND ? 404 : 503))
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private ledger"))));
    }
    doThrow(new UiLayoutResolutionException(UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR, "private token", null))
        .when(navigation).getNavigation(any(), anyString(), any(), any(), anyInt());
    mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor").header("X-Praxis-Context-Version", "c1"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PUBLICATION_CURSOR"));
  }

  @Test void malformedProviderOutputIsUnavailableRatherThanBlamingTheCaller() throws Exception {
    for (int failure = 0; failure < 3; failure++) {
      final int scenario = failure;
      doAnswer(call -> {
        if (scenario == 0) throw new IllegalArgumentException("private-provider-context");
        return new EnterpriseRuntimeNavigationResponse(scenario == 1 ? "wrong-schema" : EnterpriseRuntimeNavigationResponse.SCHEMA_VERSION,
            List.of(), List.of(), Instant.EPOCH, "c1", "private\nreceipt", List.of(), null);
      }).when(navigation).getNavigation(any(), anyString(), any(), any(), anyInt());
      mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor")
          .header("X-Praxis-Context-Version", "c1").header("If-None-Match", "*"))
          .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
  }

  @Test void invalidProviderNodeOrNullChildCannotReturn200Or304() throws Exception {
    for (int failure = 0; failure < 3; failure++) {
      final int scenario = failure;
      doAnswer(call -> {
        var node = new EnterpriseRuntimeNavigationNode(scenario == 0 ? null : scenario == 1 ? " " : "valid",
            "Label", null, null, null, null, null, null, null, null, null,
            scenario == 2 ? java.util.Arrays.asList((EnterpriseRuntimeNavigationNode) null) : null);
        return new EnterpriseRuntimeNavigationResponse(EnterpriseRuntimeNavigationResponse.SCHEMA_VERSION,
            List.of(node), List.of(), Instant.EPOCH, "c1", etag, List.of(), null);
      }).when(navigation).getNavigation(any(), anyString(), any(), any(), anyInt());
      mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor")
          .header("X-Praxis-Context-Version", "c1").header("If-None-Match", "*"))
          .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"));
    }
  }

  @Test void parametersAndMissingContextAreSanitizedBeforeProviders() throws Exception {
    mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor"))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor").header("X-Praxis-Context-Version", "c1")
        .param("pageSize", "private-invalid"))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/api/praxis/config/pages/private-invalid/published").principal(() -> "actor")
        .header("X-Praxis-Context-Version", "c1"))
        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(pages, navigation);
  }

  @Test void finalHostProjectionOwnsItsReceiptInsteadOfReusingAFragment() throws Exception {
    var first = new EnterpriseRuntimeNavigationResponse(EnterpriseRuntimeNavigationResponse.SCHEMA_VERSION,
        List.of(), List.of(), Instant.EPOCH, "c1", etag, List.of(), null);
    var second = new EnterpriseRuntimeNavigationResponse(EnterpriseRuntimeNavigationResponse.SCHEMA_VERSION,
        List.of(new EnterpriseRuntimeNavigationNode("host-entry", "Host", null, "link", "/host", null,
            null, null, null, null, null, List.of())), List.of(), Instant.EPOCH.plusSeconds(1), "c1", "b".repeat(64), List.of(), null);
    when(navigation.getNavigation(any(), eq("c1"), isNull(), isNull(), eq(50))).thenReturn(first, second);
    mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor").header("X-Praxis-Context-Version", "c1"))
        .andExpect(status().isOk()).andExpect(header().string("ETag", "W/\"" + etag + "\""));
    mvc.perform(get("/api/praxis/runtime/navigation").principal(() -> "actor").header("X-Praxis-Context-Version", "c1")
        .header("If-None-Match", "W/\"" + etag + "\""))
        .andExpect(status().isOk()).andExpect(jsonPath("$.nodes[0].id").value("host-entry"))
        .andExpect(header().string("ETag", "W/\"" + "b".repeat(64) + "\""));
  }
}
