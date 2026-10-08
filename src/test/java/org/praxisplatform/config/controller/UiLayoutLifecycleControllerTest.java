package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutReleaseHeadReceipt;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.UiLayoutLifecycleCommandService;
import org.praxisplatform.config.service.UiLayoutLifecycleException;
import org.praxisplatform.config.service.UiLayoutLifecycleReadService;
import org.praxisplatform.config.service.UiLayoutLifecycleRequestDecoder;
import org.springframework.http.HttpStatus;

@Tag("unit")
class UiLayoutLifecycleControllerTest {
  @Test void documentsTheStrictCandidateOnlyRequestAsJsonInsteadOfString() {
    var method = java.util.Arrays.stream(UiLayoutLifecycleController.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals("revision")).findFirst().orElseThrow();
    var body = method.getAnnotation(io.swagger.v3.oas.annotations.parameters.RequestBody.class);
    assertThat(body.required()).isTrue();
    assertThat(body.content()[0].mediaType()).isEqualTo("application/json");
    assertThat(body.content()[0].schema().implementation()).isEqualTo(org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest.class);
    assertThat(java.util.Arrays.stream(org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)).containsExactly("commandRef", "target", "authoringDocument", "reason");
    assertThat(org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest.class.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).additionalProperties())
        .isEqualTo(io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE);
  }
  private final UiLayoutLifecycleReadService reads = mock(UiLayoutLifecycleReadService.class);
  private final UiLayoutLifecycleCommandService commands = mock(UiLayoutLifecycleCommandService.class);
  private final UiLayoutLifecycleController controller = new UiLayoutLifecycleController(reads, commands,
      new UiLayoutLifecycleRequestDecoder(new ObjectMapper()));
  private final Principal principal = () -> "publisher";
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-dynamic-page", "procurement-master-detail");

  @Test
  void rejectsTrailingContentBeforeCallingAnyLifecycleService() {
    for (String trailing : new String[] {"{}", "INVALID"}) {
      assertThatThrownBy(() -> controller.withdraw(root.componentType(), root.componentId(), "ctx-1",
          "\"head-1\"", "{\"reason\":\"approved\"} " + trailing, principal))
          .isInstanceOf(UiLayoutLifecycleException.class)
          .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
          .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    }
    verifyNoInteractions(reads, commands);
  }

  @Test
  void readsTheAuthorizedHeadBeforeReturning304() {
    when(reads.head(principal, "ctx-1", root)).thenReturn(new UiLayoutReleaseHeadReceipt(root, null, "head-1", Instant.EPOCH));

    var response = controller.head(root.componentType(), root.componentId(), "ctx-1", "W/\"head-1\"", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    verify(reads).head(principal, "ctx-1", root);
  }

  @Test
  void rejectsMissingOrAmbiguousHeadPreconditionsBeforeTheWriter() {
    String body = "{\"releaseId\":\"" + UUID.randomUUID() + "\",\"reason\":\"approved\"}";

    assertThatThrownBy(() -> controller.publish(root.componentType(), root.componentId(), "ctx-1", null, null, body, principal))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    assertThatThrownBy(() -> controller.publish(root.componentType(), root.componentId(), "ctx-1", "\"head-1\"", "*", body, principal))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.PRECONDITION_FAILED);
    verifyNoInteractions(commands);
  }

  @Test
  void rejectsMalformedRevisionDocumentsBeforeTheWriter() {
    String body = "{\"commandRef\":\"" + UUID.randomUUID() + "\",\"target\":{"
        + "\"componentType\":\"praxis-table\",\"componentId\":\"orders\"},"
        + "\"authoringDocument\":null,\"patchDocument\":{},\"reason\":\"edit\"}";

    assertThatThrownBy(() -> controller.revision(UUID.randomUUID(), root.componentType(), root.componentId(),
        "ctx-1", "\"" + UUID.randomUUID() + "\"", body, principal))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
    verifyNoInteractions(commands);
  }
  @Test void mapsExplicitAdmissionOutcomesToSanitizedHttpCategories() {
    var handler = new UiLayoutResolutionExceptionHandler();
    var codes = new UiLayoutLifecycleException.Code[] {
        UiLayoutLifecycleException.Code.DENIED, UiLayoutLifecycleException.Code.CONTEXT_STALE,
        UiLayoutLifecycleException.Code.VALIDATION_FAILED, UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE};
    var statuses = new HttpStatus[] {HttpStatus.FORBIDDEN, HttpStatus.CONFLICT,
        HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.SERVICE_UNAVAILABLE};
    for (int index = 0; index < codes.length; index++) {
      var response = handler.lifecycle(new UiLayoutLifecycleException(codes[index], "private credential"));
      assertThat(response.getStatusCode()).isEqualTo(statuses[index]);
      assertThat(response.getBody().getProperties().get("code")).isEqualTo(codes[index].name());
      assertThat(response.getBody().getDetail()).doesNotContain("private");
      assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
  }
}
