package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class UiLayoutPageNeonAssignmentTest {
  private ObjectNode assigned() {
    return new ObjectMapper().createObjectNode().put("projectId","test-project-123456")
        .put("branchId","br-isolated-123456").put("endpoint","ep-isolated-123456.us-east-1.aws.neon.tech")
        .put("schema","b1a_it_page_unit").put("username","p1_page_unit_runtime").put("password","not-a-real-secret")
        .put("jdbcUrl","jdbc:postgresql://ep-isolated-123456.us-east-1.aws.neon.tech/neondb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory");
  }
  @Test void acceptsExplicitAssignmentWithoutFreezingOneSessionIdentity() {
    assertThatCode(()->UiLayoutPageLifecyclePostgresIT.requireAssignment(assigned())).doesNotThrowAnyException();
  }
  @Test void rejectsUnassignedSchemaPrivilegedRoleMissingBranchAndUnsafeTransport() {
    for (String field : java.util.List.of("schema","username","branchId","endpoint","password")) {
      var value=assigned().put(field,"");
      assertThatThrownBy(()->UiLayoutPageLifecyclePostgresIT.requireAssignment(value)).isInstanceOf(IllegalArgumentException.class);
    }
    for (String url : java.util.List.of(assigned().path("jdbcUrl").asText().replace("verify-full","require"),
        assigned().path("jdbcUrl").asText().replace("ep-isolated-123456","ep-other-123456"))) {
      var value=assigned().put("jdbcUrl",url);
      assertThatThrownBy(()->UiLayoutPageLifecyclePostgresIT.requireAssignment(value)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
