package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Receipt/cursor describe the final host projection, not an unmodified Config fragment. */
public record EnterpriseRuntimeNavigationResponse(
    String schemaVersion, List<EnterpriseRuntimeNavigationNode> nodes, List<String> capabilities,
    Instant resolvedAt, String contextVersion, String receiptEtag,
    List<UiLayoutReleaseHeadReceipt> headReceipts, String nextCursor) {
  public static final String SCHEMA_VERSION = "praxis-enterprise-runtime-navigation.v1";
  public EnterpriseRuntimeNavigationResponse {
    if (!SCHEMA_VERSION.equals(schemaVersion) || contextVersion == null || contextVersion.isBlank()
        || receiptEtag == null || receiptEtag.isBlank()) throw new IllegalArgumentException("Invalid navigation receipt.");
    Objects.requireNonNull(resolvedAt);
    nodes = List.copyOf(nodes);
    capabilities = List.copyOf(capabilities);
    headReceipts = List.copyOf(headReceipts);
  }
}
