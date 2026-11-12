package org.praxisplatform.config.controller;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.config.domain.*;
import org.praxisplatform.config.repository.*;
import org.praxisplatform.config.service.*;
import org.praxisplatform.config.exception.DomainRuleSnapshotControlPlaneException;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real HTTP binding, authorization port and native CAS guards. SQL rollback has the Neon gate. */
@Tag("unit")
class DomainRuleSnapshotWithdrawalHttpTest {
  final DomainRuleSnapshotHeadRepository heads=mock(DomainRuleSnapshotHeadRepository.class);
  final DomainRuleSnapshotRepository rows=mock(DomainRuleSnapshotRepository.class);
  final DomainRuleSnapshotEventRepository events=mock(DomainRuleSnapshotEventRepository.class);
  final DomainRuleGovernancePrincipalResolver principals=mock(DomainRuleGovernancePrincipalResolver.class);
  DomainRuleSnapshotHead head;
  MockMvc mvc;
  static final String URL="/api/praxis/config/domain-rules/snapshots/withdrawals";
  static final String BODY="{\"ruleSetKey\":\"family\",\"operationKey\":\"create\",\"reason\":\"Reviewed withdrawal\"}";
  @BeforeEach void setup() {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    var service=new DomainRuleSnapshotService(mock(DomainRuleDefinitionRepository.class),rows,heads,events,
        mock(DomainRuleCompositionApprovalRepository.class),mock(DomainRuleDefinitionApprovalRepository.class),
        new DomainRuleDefinitionFingerprint(mapper),mapper,DomainRuleImplementationCatalog.denyAll(),mock(DomainRuleSnapshotActivationGate.class),null,
        mock(DomainRuleSnapshotScopeLock.class),mock(DomainRuleEntityRefresh.class),(scope,identity)->Optional.empty());
    head=DomainRuleSnapshotHead.builder().id(UUID.randomUUID()).tenantId("2").environment("test").ruleSetKey("family").operationKey("create")
        .activeSnapshotId(UUID.randomUUID()).headEtag(UUID.randomUUID()).activationRevision(4L).build();
    when(heads.findByTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey("2","test","family","create")).thenReturn(Optional.of(head));
    when(rows.findByIdAndTenantIdAndEnvironmentAndRuleSetKeyAndOperationKey(head.getActiveSnapshotId(),"2","test","family","create"))
        .thenReturn(Optional.of(DomainRuleSnapshot.builder().id(head.getActiveSnapshotId()).snapshotKey("last-snapshot").build()));
    when(principals.resolve(any(),eq("2"),eq("test"),eq("RULE_SNAPSHOT_OPERATOR")))
        .thenReturn(new DomainRuleGovernancePrincipal("2","authenticated-operator","test"));
    mvc=MockMvcBuilders.standaloneSetup(new DomainRuleSnapshotController(service,principals)).build();
  }
  @Test void withdrawalReturnsTombstoneEtagNoStoreAndAuthenticatedAudit() throws Exception {
    mvc.perform(post(URL).header("X-Tenant-ID","2").header("X-Env","test").header("If-Match",'"'+head.getHeadEtag().toString()+'"')
        .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
        .andExpect(header().exists("ETag")).andExpect(jsonPath("$.activationRevision").value(5))
        .andExpect(jsonPath("$.lastSnapshotKey").value("last-snapshot")).andExpect(jsonPath("$.snapshot").doesNotExist());
    verify(events).save(argThat(event->event.getEventType().equals("WITHDRAWN") && event.getActor().equals("authenticated-operator") && event.getReason().equals("Reviewed withdrawal")));
  }
  @ParameterizedTest @NullSource @ValueSource(strings={"*","W/\"head\"","\"stale\""})
  void missingWeakWildcardAndStaleValidatorsCannotWithdraw(String condition) throws Exception {
    var request=post(URL).header("X-Tenant-ID","2").header("X-Env","test").contentType(MediaType.APPLICATION_JSON).content(BODY);
    if(condition!=null)request.header("If-Match",condition);
    mvc.perform(request).andExpect(status().is(condition==null?428:412));
    verify(heads,never()).saveAndFlush(any());verify(events,never()).save(any());
  }
  @Test void deniedOperatorCannotReachTheHeadOrAppendAnEvent() throws Exception {
    when(principals.resolve(any(),eq("2"),eq("test"),eq("RULE_SNAPSHOT_OPERATOR")))
        .thenThrow(new DomainRuleSnapshotControlPlaneException(HttpStatus.FORBIDDEN,"Operator role required"));
    mvc.perform(post(URL).header("X-Tenant-ID","2").header("X-Env","test").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
    verifyNoInteractions(heads,events);
  }
  @Test void missingReasonIsAnInputFailureWithoutMutation() throws Exception {
    mvc.perform(post(URL).header("X-Tenant-ID","2").header("X-Env","test").header("If-Match",'"'+head.getHeadEtag().toString()+'"')
        .contentType(MediaType.APPLICATION_JSON).content("{\"ruleSetKey\":\"family\",\"operationKey\":\"create\"}"))
        .andExpect(status().isBadRequest());verify(heads,never()).saveAndFlush(any());verify(events,never()).save(any());
  }
}
