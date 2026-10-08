package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class UiLayoutRevisionRequestBodyAdviceTest {
  private final UiLayoutRevisionRequestBodyAdvice advice = new UiLayoutRevisionRequestBodyAdvice();
  private final UiLayoutLifecycleReadService reads = mock(UiLayoutLifecycleReadService.class);
  private final UiLayoutLifecycleCommandService commands = mock(UiLayoutLifecycleCommandService.class);
  private final int limit = UiLayoutLifecycleRequestDecoder.MAX_REVISION_BODY_BYTES;

  private MethodParameter parameter(String method) {
    for (var candidate : UiLayoutLifecycleController.class.getMethods()) {
      if (candidate.getName().equals(method)) {
        var types = candidate.getParameterTypes();
        for (int index = 0; index < types.length; index++) if (types[index] == String.class
            && candidate.getParameters()[index].isAnnotationPresent(org.springframework.web.bind.annotation.RequestBody.class))
          return new MethodParameter(candidate, index);
      }
    }
    throw new IllegalStateException();
  }
  private HttpInputMessage input(byte[] body, long declared, String media, AtomicInteger reads) {
    var headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(media));
    if (declared >= 0) headers.setContentLength(declared); else headers.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
    return new HttpInputMessage() {
      public HttpHeaders getHeaders() { return headers; }
      public InputStream getBody() { reads.incrementAndGet(); return new ByteArrayInputStream(body); }
    };
  }
  private HttpInputMessage bounded(HttpInputMessage input) throws Exception {
    return advice.beforeBodyRead(input, parameter("revision"), String.class, StringHttpMessageConverter.class);
  }
  private org.springframework.test.web.servlet.MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new UiLayoutLifecycleController(reads, commands,
        new UiLayoutLifecycleRequestDecoder(new ObjectMapper())))
        .setControllerAdvice(new UiLayoutResolutionExceptionHandler(), advice).build();
  }
  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder revision(byte[] body) {
    return post("/api/praxis/config/ui-layouts/drafts/00000000-0000-4000-8000-000000000001/revisions")
        .param("rootComponentType", "praxis-table").param("rootComponentId", "orders")
        .header("X-Praxis-Context-Version", "ctx-1").header("If-Match", "\"00000000-0000-4000-8000-000000000002\"")
        .contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test void appliesOnlyToTheRevisionBodyParameter() {
    assertThat(advice.supports(parameter("revision"), String.class, StringHttpMessageConverter.class)).isTrue();
    assertThat(advice.supports(parameter("assignment"), String.class, StringHttpMessageConverter.class)).isFalse();
    assertThat(advice.supports(parameter("withdraw"), String.class, StringHttpMessageConverter.class)).isFalse();
  }

  @Test void rejectsDeclaredOversizeBeforeObtainingTheStream() {
    var reads = new AtomicInteger(); var input = input(new byte[0], limit + 1L, "application/json", reads);
    assertThatThrownBy(() -> bounded(input)).isInstanceOf(UiLayoutRevisionRequestBodyAdvice.BodyTooLarge.class);
    assertThat(reads).hasValue(0);
  }

  @Test void acceptsExactActualLimitAndRejectsAnExtraByteWithoutContentLength() throws Exception {
    byte[] exact = "x".repeat(limit).getBytes(StandardCharsets.UTF_8);
    assertThat(bounded(input(exact, -1, "application/json", new AtomicInteger())).getBody().readAllBytes()).hasSize(limit);
    byte[] extra = "x".repeat(limit + 1).getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> bounded(input(extra, -1, "application/json", new AtomicInteger())))
        .isInstanceOf(UiLayoutRevisionRequestBodyAdvice.BodyTooLarge.class);
  }

  @Test void usesActualBytesWhenDeclaredLengthIsSmallerAndNormalizesOnlyWrapperHeaders() throws Exception {
    byte[] raw = "{} trailing".getBytes(StandardCharsets.UTF_8);
    var original = input(raw, 2, "application/json", new AtomicInteger());
    var admitted = bounded(original);
    assertThat(admitted.getHeaders().getContentLength()).isEqualTo(raw.length);
    assertThat(original.getHeaders().getContentLength()).isEqualTo(2);
    assertThat(new StringHttpMessageConverter(StandardCharsets.UTF_8).read(String.class, admitted)).isEqualTo("{} trailing");
    assertThatThrownBy(() -> bounded(input("x".repeat(limit + 1).getBytes(StandardCharsets.UTF_8), 2,
        "application/json", new AtomicInteger()))).isInstanceOf(UiLayoutRevisionRequestBodyAdvice.BodyTooLarge.class);
  }

  @Test void rejectsMalformedUtf8AndNonUtf8DeclaredCharset() {
    assertThatThrownBy(() -> bounded(input(new byte[] {(byte) 0xc3, 0x28}, -1, "application/json", new AtomicInteger())))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(() -> bounded(input(new byte[0], -1, "application/json;charset=ISO-8859-1", new AtomicInteger())))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test void returnsSanitized413BeforeDecoderOrLifecycleMutation() throws Exception {
    mvc().perform(revision("private".repeat(160000).getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REVISION_BODY_TOO_LARGE"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    verifyNoInteractions(reads, commands);
  }

  @Test void rejectsMalformedEncodingAndOversizedDocumentAs400WithoutMutation() throws Exception {
    mvc().perform(revision(new byte[] {(byte) 0xc3, 0x28})).andExpect(status().isBadRequest());
    String body = "{\"commandRef\":\"00000000-0000-4000-8000-000000000001\",\"target\":{\"componentType\":\"praxis-table\",\"componentId\":\"orders\"},"
        + "\"authoringDocument\":{\"v\":\"" + "x".repeat(262144) + "\"},\"reason\":\"test\"}";
    mvc().perform(revision(body.getBytes(StandardCharsets.UTF_8))).andExpect(status().isBadRequest());
    verifyNoInteractions(reads, commands);
  }

  @Test void servletSecurityDenialRunsBeforeMvcAdviceWithExplicitTestDouble() throws Exception {
    var secured = MockMvcBuilders.standaloneSetup(new UiLayoutLifecycleController(reads, commands,
        new UiLayoutLifecycleRequestDecoder(new ObjectMapper())))
        .setControllerAdvice(new UiLayoutResolutionExceptionHandler(), advice)
        .addFilters((jakarta.servlet.Filter) (request, response, chain) -> {
          ((jakarta.servlet.http.HttpServletResponse) response).setStatus(403);
          // This double stops the servlet chain before MVC. It is not a real IAM/Origin grant proof.
        }).build();
    secured.perform(revision("private".repeat(160000).getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isForbidden());
    verifyNoInteractions(reads, commands);
  }
}
