package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

@Tag("unit")
class UiLayoutRevisionJsonInputTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Runnable active = () -> {};
  private void invalid(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        failure -> assertThat(failure.getCode()).isEqualTo(Code.INVALID_REQUEST));
  }
  private String body(String candidate) {
    return "{\"commandRef\":\"00000000-0000-4000-8000-000000000001\",\"target\":{\"componentType\":\"praxis-table\",\"componentId\":\"orders\"},"
        + "\"authoringDocument\":" + candidate + ",\"reason\":\"test\"}";
  }
  private String nested(int depth) throws Exception {
    var root = mapper.createObjectNode(); var child = root;
    for (int index = 1; index < depth; index++) child = child.putObject("c");
    return root.toString();
  }

  @Test void rejectsLeniencyDuplicatesTrailingAndNonObjectsForDirectJavaEntry() {
    for (String raw : new String[] {"{/*comment*/\"a\":1}", "{\"a\":1,\"a\":2}", "{} {}", "{} INVALID", "[]", "null"})
      invalid(() -> UiLayoutRevisionJsonInput.readDocument(raw, active));
  }

  @Test void boundsTextBeforeParsingAndPreservesExpiry() {
    invalid(() -> UiLayoutRevisionJsonInput.readDocument(" ".repeat(1048577), active));
    var checks = new AtomicInteger();
    assertThatThrownBy(() -> UiLayoutRevisionJsonInput.readDocument("{}", () -> {
      if (checks.incrementAndGet() > 2) throw new UiLayoutLifecycleException(Code.SOURCE_UNAVAILABLE, "expired");
    })).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        failure -> assertThat(failure.getCode()).isEqualTo(Code.SOURCE_UNAVAILABLE));
  }

  @Test void acceptsDocumentDepth64WithHttpWrapper65AndRejects65Native() throws Exception {
    var decoder = new UiLayoutLifecycleRequestDecoder(mapper);
    UiLayoutRevisionJsonInput.readDocument(nested(64), active);
    decoder.decode(body(nested(64)), UiLayoutRevisionCommandRequest.class);
    String tooDeep = nested(65);
    invalid(() -> UiLayoutRevisionJsonInput.readDocument(tooDeep, active));
    invalid(() -> decoder.decode(body(tooDeep), UiLayoutRevisionCommandRequest.class));
  }

  @Test void keepsCandidateQuotaIndependentOfTheWholeBodyQuota() {
    var decoder = new UiLayoutLifecycleRequestDecoder(mapper);
    String exact = "{\"v\":\"" + "x".repeat(262136) + "\"}";
    UiLayoutRevisionJsonInput.readDocument(exact, active);
    decoder.decode(body(exact), UiLayoutRevisionCommandRequest.class);
    String extra = "{\"v\":\"" + "x".repeat(262137) + "\"}";
    invalid(() -> UiLayoutRevisionJsonInput.readDocument(extra, active));
    invalid(() -> decoder.decode(body(extra), UiLayoutRevisionCommandRequest.class));
  }

  @Test void rejectsEscapedMalformedUnicodeAndLongNumericTokensBeforeAcceptance() {
    for (String raw : new String[] {"{\"v\":\"\\uD800\"}", "{\"v\":" + "1".repeat(257) + "}"}) {
      invalid(() -> UiLayoutRevisionJsonInput.readDocument(raw, active));
      invalid(() -> new UiLayoutLifecycleRequestDecoder(mapper).decode(body(raw), UiLayoutRevisionCommandRequest.class));
    }
  }
}
