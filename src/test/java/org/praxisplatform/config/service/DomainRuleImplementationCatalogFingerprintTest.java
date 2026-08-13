package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.RuleImplementationRef;

@Tag("unit")
class DomainRuleImplementationCatalogFingerprintTest {
  @Test
  void isDeterministicAcrossImplementationOrderAndBoundToScope() {
    var mapper = new ObjectMapper();
    var scope = new DomainRuleImplementationScope("tenant-a", "dev", "quickstart");
    var first = new RuleImplementationRef("benefits:z", "2.0.0");
    var second = new RuleImplementationRef("benefits:a", "1.0.0");

    String forward = DomainRuleImplementationCatalogFingerprint.sha256(
        mapper, scope, List.of(first, second));
    String reverse = DomainRuleImplementationCatalogFingerprint.sha256(
        mapper, scope, List.of(second, first));
    String otherEnvironment = DomainRuleImplementationCatalogFingerprint.sha256(
        mapper, new DomainRuleImplementationScope("tenant-a", "prod", "quickstart"),
        List.of(first, second));

    assertThat(forward).matches("[A-F0-9]{64}").isEqualTo(reverse);
    assertThat(otherEnvironment).isNotEqualTo(forward);
  }
}
