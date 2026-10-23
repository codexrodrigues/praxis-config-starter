package org.praxisplatform.config.controller;

import org.springframework.http.CacheControl;
import org.praxisplatform.config.http.HttpEntityTagCondition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Weak GET validators: resolvedAt is an observation, while receiptEtag is semantic identity. */
final class PublishedReadHttp {
  private PublishedReadHttp() {}

  static <T> ResponseEntity<T> response(T value, String receipt, String condition) {
    if (value == null || receipt == null || receipt.isBlank() || receipt.length() > 255
        || receipt.chars().anyMatch(c -> c < 33 || c > 126 || c == '"' || c == '\\')) {
      throw new org.praxisplatform.config.service.UiLayoutLifecycleException(
          org.praxisplatform.config.service.UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Published representation is unavailable.");
    }
    String quoted = "\"" + receipt + "\"";
    String weak = "W/" + quoted;
    boolean matches;
    try {
      if (condition != null && condition.length() > 8192) throw new IllegalArgumentException();
      matches = HttpEntityTagCondition.parse(condition).matchesWeak(receipt);
    } catch (IllegalArgumentException invalid) {
      throw new org.praxisplatform.config.service.UiLayoutResolutionException(
          org.praxisplatform.config.service.UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR,
          "Invalid conditional header.", null);
    }
    return ResponseEntity.status(matches ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
        .cacheControl(CacheControl.noCache().cachePrivate()).eTag(weak).body(matches ? null : value);
  }
}
