package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.EffectiveUiLayoutCompositionResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.UiLayoutCompositionReadService;
import org.praxisplatform.config.service.UiLayoutResolutionException;
import org.springframework.http.HttpStatus;

@Tag("unit")
class EffectiveUiLayoutCompositionControllerTest {
  private final UiLayoutCompositionReadService service = mock(UiLayoutCompositionReadService.class);
  private final EffectiveUiLayoutCompositionController controller = new EffectiveUiLayoutCompositionController(service);
  private final Principal principal = () -> "reader";

  @Test
  void resolvesAuthorizationAndContextBeforeReturning304() {
    when(service.resolve(principal, "ctx-1", new UiLayoutTarget("praxis-dynamic-page", "procurement")))
        .thenReturn(receipt("etag-1"));

    var response = controller.effectiveComposition("praxis-dynamic-page", "procurement", "ctx-1", "W/\"etag-1\"", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    verify(service).resolve(principal, "ctx-1", new UiLayoutTarget("praxis-dynamic-page", "procurement"));
  }

  @Test
  void rejectsMissingPrincipalBeforeItCanEvaluateAConditionalHeader() {
    assertThatThrownBy(() -> controller.effectiveComposition("praxis-dynamic-page", "procurement", "ctx-1", "W/\"etag-1\"", null))
        .isInstanceOf(UiLayoutResolutionException.class)
        .extracting(error -> ((UiLayoutResolutionException) error).code())
        .isEqualTo(UiLayoutResolutionException.Code.SESSION_REQUIRED);
  }

  private EffectiveUiLayoutCompositionResponse receipt(String etag) {
    return new EffectiveUiLayoutCompositionResponse("praxis.effective-ui-layout-composition/v1", null,
        new UiLayoutTarget("praxis-dynamic-page", "procurement"), "ctx-1", etag, List.of(), Instant.parse("2026-09-10T15:00:00Z"));
  }
}
