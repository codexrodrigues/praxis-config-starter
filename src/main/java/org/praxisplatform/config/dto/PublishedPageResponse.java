package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** Whole admitted native publication. No mutable workspace, lineage ledger or personal overlay. */
public record PublishedPageResponse(String schemaVersion, UUID pageId, String route,
    JsonNode document, String contextVersion, String receiptEtag,
    UiLayoutReleaseHeadReceipt head, Instant resolvedAt) {
  public static final String SCHEMA_VERSION = "praxis.published-page/v1";
}
