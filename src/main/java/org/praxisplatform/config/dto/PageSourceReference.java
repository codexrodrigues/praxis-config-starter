package org.praxisplatform.config.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.UUID;

/** An exact origin selector, never an origin attestation or executable URL. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({@JsonSubTypes.Type(value = PageSourceReference.Native.class, name = "registered-native"),
    @JsonSubTypes.Type(value = PageSourceReference.Published.class, name = "published-page")})
public sealed interface PageSourceReference permits PageSourceReference.Native, PageSourceReference.Published {
  record Native(String sourceId, String revision) implements PageSourceReference {}
  record Published(UUID pageId, UUID releaseId, UUID revisionId) implements PageSourceReference {}
}
