package org.praxisplatform.config.dto;

import java.util.UUID;

/**
 * Stable creation identity; draftEtag is the current quoted validator, not an immutable historical
 * receipt.
 */
public record PageCreationReceipt(
    UUID creationKey,
    UUID pageId,
    UiLayoutTarget target,
    String routePath,
    UUID draftId,
    String draftEtag,
    PageSourceReference source) {}
