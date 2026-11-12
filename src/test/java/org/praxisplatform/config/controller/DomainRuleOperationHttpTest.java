package org.praxisplatform.config.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.*;
import org.praxisplatform.config.service.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real MVC binding verifies that a family-only request cannot silently resolve an operation. */
@Tag("unit")
class DomainRuleOperationHttpTest {
  DomainRuleSnapshotService snapshots;DomainRuleGovernancePrincipalResolver principals;MockMvc http;
  @BeforeEach void prepare() {
    snapshots=mock(DomainRuleSnapshotService.class);principals=mock(DomainRuleGovernancePrincipalResolver.class);
    http=MockMvcBuilders.standaloneSetup(new DomainRuleSnapshotController(snapshots,principals)).build();
  }
  @Test void missingOperationCannotReadAHead()throws Exception {
    http.perform(get("/api/praxis/config/domain-rules/snapshots/head").param("ruleSetKey","family"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(snapshots);
  }
  @Test void missingOperationCannotListVersions()throws Exception {
    http.perform(get("/api/praxis/config/domain-rules/snapshots").param("ruleSetKey","family"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(snapshots);
  }
  @Test void authenticatedScopeAndOperationReachTheCanonicalReadWithoutFallback()throws Exception {
    when(principals.resolve(any(),eq("2"),eq("stage"),eq("RULE_SNAPSHOT_READER")))
        .thenReturn(new DomainRuleGovernancePrincipal("2","reader","stage"));
    when(snapshots.findActive("2","stage","family","delete")).thenReturn(Optional.empty());
    http.perform(get("/api/praxis/config/domain-rules/snapshots/head").param("ruleSetKey","family")
        .param("operationKey","delete").header("X-Tenant-ID","2").header("X-Env","stage"))
        .andExpect(status().isNotFound());
    verify(snapshots).findActive("2","stage","family","delete");verifyNoMoreInteractions(snapshots);
  }
}
