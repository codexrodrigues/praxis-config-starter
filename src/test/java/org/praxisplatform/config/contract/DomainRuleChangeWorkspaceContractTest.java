package org.praxisplatform.config.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.DomainRuleChangeWorkspaceController;
import org.springframework.web.bind.annotation.RequestMapping;

class DomainRuleChangeWorkspaceContractTest {

  @Test
  void publishesStableSemanticIdentityAndBindsTheControllerToItsCanonicalPath() {
    assertEquals(
        "praxis.config.domain-rule-change-workspaces",
        DomainRuleChangeWorkspaceContract.RESOURCE_KEY);
    assertEquals(
        "/api/praxis/config/domain-rules/workspaces",
        DomainRuleChangeWorkspaceContract.RESOURCE_PATH);

    RequestMapping mapping = DomainRuleChangeWorkspaceController.class.getAnnotation(RequestMapping.class);
    assertArrayEquals(
        new String[] {DomainRuleChangeWorkspaceContract.RESOURCE_PATH},
        mapping.value());
  }
}
