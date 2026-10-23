package org.praxisplatform.config.dto;

import java.util.UUID;

/** Published identity projection, never an independent publication pointer. */
public record PageCatalogEntry(UUID pageId, UiLayoutTarget rootTarget, String route,
    String title, String icon, UiLayoutReleaseHeadReceipt head) {}
