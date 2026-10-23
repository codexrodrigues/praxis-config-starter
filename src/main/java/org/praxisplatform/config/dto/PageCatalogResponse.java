package org.praxisplatform.config.dto;

import java.time.Instant;
import java.util.List;

public record PageCatalogResponse(String schemaVersion, List<PageCatalogEntry> items,
    String contextVersion, String receiptEtag, String nextCursor, Instant resolvedAt) {
  public static final String SCHEMA_VERSION = "praxis.page-catalog/v1";
  public PageCatalogResponse { items = List.copyOf(items); }
}
