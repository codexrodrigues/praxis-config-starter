package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PageRuntimeCursorTest {
  @Test void positionIsUntrustedAndMustStillExistInTheCurrentAuthorizedView() {
    var cursor = new PageRuntimeCursor("a".repeat(64), "b".repeat(64), new PageRuntimeCursor.Key(7, "entry", "page"));
    assertThat(PageRuntimeCursor.decode(cursor.encode())).isEqualTo(cursor);
    cursor.requireBinding("a".repeat(64));
    cursor.requireView("b".repeat(64), true);
    assertThatThrownBy(() -> cursor.requireView("c".repeat(64), true)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_CONTINUATION_STALE);
    assertThatThrownBy(() -> cursor.requireBinding("c".repeat(64))).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.PUBLICATION_CONTINUATION_STALE);
    assertThatThrownBy(() -> cursor.requireView("b".repeat(64), false)).extracting("code")
        .isEqualTo(UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR);
  }

  @Test void malformedDuplicateUnknownTrailingAndOversizedCursorFailClosed() {
    String valid = "{\"version\":1,\"binding\":\"" + "a".repeat(64) + "\",\"view\":\"" + "b".repeat(64)
        + "\",\"last\":[0,\"entry\",\"page\"]}";
    for (String bad : new String[] {valid + " {}", valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
        valid.replace("\"version\":1", "\"version\":1,\"unknown\":true"), valid.replace("[0,", "[0.5,"), "null", "{}"}) {
      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bad.getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> PageRuntimeCursor.decode(token)).extracting("code")
          .isEqualTo(UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR);
    }
    assertThatThrownBy(() -> PageRuntimeCursor.decode("a".repeat(4097))).isInstanceOf(UiLayoutResolutionException.class);
  }
}
