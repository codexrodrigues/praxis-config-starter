package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Whole admitted native publication. No mutable workspace, lineage ledger or personal overlay. */
public record PublishedPageResponse(String schemaVersion, UUID pageId, String route,
    JsonNode document, String contextVersion, String receiptEtag,
    UiLayoutReleaseHeadReceipt head,
    @Schema(description = "Exact page, release and content revision of this admitted publication. Selects a copy origin; copy authorization is rechecked by creation.", requiredMode = Schema.RequiredMode.REQUIRED)
    PageSourceReference.Published copySource, Instant resolvedAt) {
  public static final String SCHEMA_VERSION = "praxis.published-page/v1";
}
