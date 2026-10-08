package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.config.dto.UiLayoutReasonRequest;
import org.praxisplatform.config.dto.UiLayoutAssignmentCommandRequest;
import org.praxisplatform.config.dto.UiLayoutFreezeReleaseRequest;
import org.praxisplatform.config.dto.UiLayoutHeadReleaseRequest;
import org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest;

@Tag("unit")
class UiLayoutLifecycleRequestDecoderTest {
  @ParameterizedTest
  @ValueSource(strings = {"{}", "[]", "null", "true", "42", "\"tail\"", "INVALID"})
  void rejectsEveryTokenAfterTheSingleCommandDocument(String trailing) {
    assertInvalid(new UiLayoutLifecycleRequestDecoder(new ObjectMapper()),
        "{\"reason\":\"approved\"} " + trailing, UiLayoutReasonRequest.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{/*comment*/\"reason\":\"approved\"}",
      "#comment\n{\"reason\":\"approved\"}",
      "{'reason':'approved'}",
      "{reason:\"approved\"}",
      "{\"reason\":\"approved\",}",
      "{\"reason\":\"approved\tvalue\"}"
  })
  void rejectsNonJsonSyntaxEvenWhenTheHostEnablesAllLexicalLeniency(String body) {
    ObjectMapper host = new ObjectMapper();
    for (JsonReadFeature feature : JsonReadFeature.values()) host.enable(feature.mappedFeature());
    assertInvalid(new UiLayoutLifecycleRequestDecoder(host), body, UiLayoutReasonRequest.class);
  }

  @Test
  void acceptsValidJsonAndWhitespaceWithoutReconfiguringTheHostMapper() throws Exception {
    ObjectMapper host = new ObjectMapper().enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature());
    UiLayoutLifecycleRequestDecoder decoder = new UiLayoutLifecycleRequestDecoder(host);
    assertThat(decoder.decode(" \n{\"reason\":\"approved \\u00e9\"}\r\n\t", UiLayoutReasonRequest.class).reason())
        .isEqualTo("approved é");
    assertThat(host.isEnabled(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature())).isTrue();
    assertThat(host.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
    assertThat(host.readTree("{/*comment*/\"reason\":\"approved\"} {}").path("reason").asText())
        .isEqualTo("approved");
  }

  @Test
  void rejectsUnknownCommandPropertiesWithoutChangingTheGlobalMapper() {
    UiLayoutLifecycleRequestDecoder decoder = new UiLayoutLifecycleRequestDecoder(new ObjectMapper());

    assertThatThrownBy(() -> decoder.decode("{\"reason\":\"approved\",\"actor\":\"forged\"}", UiLayoutReasonRequest.class))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
  }

  @Test
  void rejectsDuplicateFieldsAndScalarBodies() {
    UiLayoutLifecycleRequestDecoder decoder = new UiLayoutLifecycleRequestDecoder(new ObjectMapper());

    assertThatThrownBy(() -> decoder.decode("{\"reason\":\"first\",\"reason\":\"forged\"}", UiLayoutReasonRequest.class))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    assertThatThrownBy(() -> decoder.decode("\"approved\"", UiLayoutReasonRequest.class))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
  }

  @Test
  void rejectsNullEmptyAndNonObjectBodies() {
    UiLayoutLifecycleRequestDecoder decoder = new UiLayoutLifecycleRequestDecoder(new ObjectMapper());

    assertInvalid(decoder, null, UiLayoutReasonRequest.class);
    assertInvalid(decoder, "null", UiLayoutReasonRequest.class);
    assertInvalid(decoder, "{}", UiLayoutReasonRequest.class);
    assertInvalid(decoder, "[]", UiLayoutReasonRequest.class);
    assertInvalid(decoder, "\"approved\"", UiLayoutReasonRequest.class);
  }

  @Test
  void rejectsMissingNullAndScalarRevisionDocuments() {
    UiLayoutLifecycleRequestDecoder decoder = new UiLayoutLifecycleRequestDecoder(new ObjectMapper());
    String command = "\"commandRef\":\"" + UUID.randomUUID() + "\",\"target\":{"
        + "\"componentType\":\"praxis-table\",\"componentId\":\"orders\"},";

    assertInvalid(decoder, "{" + command + "\"patchDocument\":{},\"reason\":\"edit\"}", UiLayoutRevisionCommandRequest.class);
    assertInvalid(decoder, "{" + command + "\"authoringDocument\":null,\"patchDocument\":{},\"reason\":\"edit\"}", UiLayoutRevisionCommandRequest.class);
    assertInvalid(decoder, "{" + command + "\"authoringDocument\":[],\"patchDocument\":{},\"reason\":\"edit\"}", UiLayoutRevisionCommandRequest.class);
    decoder.decode("{" + command + "\"authoringDocument\":{},\"reason\":\"edit\"}", UiLayoutRevisionCommandRequest.class);
    assertInvalid(decoder, "{" + command + "\"authoringDocument\":{},\"patchDocument\":null,\"reason\":\"edit\"}", UiLayoutRevisionCommandRequest.class);
    assertInvalid(decoder, "{" + command + "\"authoringDocument\":{},\"patchDocument\":\"merge\",\"reason\":\"edit\"}", UiLayoutRevisionCommandRequest.class);
  }

  @Test
  void rejectsMalformedRequiredFieldsAcrossLifecycleCommands() {
    UiLayoutLifecycleRequestDecoder decoder = new UiLayoutLifecycleRequestDecoder(new ObjectMapper());
    String commandRef = UUID.randomUUID().toString();
    String target = "{\"componentType\":\"praxis-table\",\"componentId\":\"orders\"}";

    assertInvalid(decoder, "{}", UiLayoutFreezeReleaseRequest.class);
    assertInvalid(decoder, "{\"commandRef\":null}", UiLayoutFreezeReleaseRequest.class);
    assertInvalid(decoder, "{\"commandRef\":\"" + commandRef + "\",\"target\":" + target
        + ",\"layerClass\":\"TENANT\",\"selector\":{\"tenant\":\"tenant-a\"},\"contributionKey\":\"primary\"}",
        UiLayoutAssignmentCommandRequest.class);
    assertInvalid(decoder, "{\"commandRef\":\"" + commandRef + "\",\"target\":" + target
        + ",\"layerClass\":\"TENANT\",\"selector\":{},\"priority\":1,\"contributionKey\":\"primary\"}",
        UiLayoutAssignmentCommandRequest.class);
    assertInvalid(decoder, "{\"reason\":\"publish\"}", UiLayoutHeadReleaseRequest.class);
    assertInvalid(decoder, "{\"reason\":\"   \"}", UiLayoutReasonRequest.class);
  }

  @Test
  void rejectsReplacedClientSelectedRevisionAndFreezeMemberContracts() {
    UiLayoutLifecycleRequestDecoder decoder = new UiLayoutLifecycleRequestDecoder(new ObjectMapper());

    assertThatThrownBy(() -> decoder.decode("{\"revisionId\":\"00000000-0000-0000-0000-000000000001\"}",
        UiLayoutAssignmentCommandRequest.class))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    assertThatThrownBy(() -> decoder.decode("{\"commandRef\":\"00000000-0000-0000-0000-000000000001\",\"members\":[]}",
        UiLayoutFreezeReleaseRequest.class))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
  }

  private <T> void assertInvalid(UiLayoutLifecycleRequestDecoder decoder, String body, Class<T> type) {
    assertThatThrownBy(() -> decoder.decode(body, type))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
  }
}
