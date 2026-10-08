package org.praxisplatform.config.controller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.praxisplatform.config.service.UiLayoutLifecycleException;
import org.praxisplatform.config.service.UiLayoutLifecycleRequestDecoder;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Scoped MVC entry control, after host servlet security filters and before String conversion. */
@ControllerAdvice(assignableTypes = UiLayoutLifecycleController.class)
public class UiLayoutRevisionRequestBodyAdvice extends RequestBodyAdviceAdapter {
  @Override public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
    return UiLayoutLifecycleController.class.isAssignableFrom(parameter.getContainingClass())
        && parameter.getMethod() != null && parameter.getMethod().getName().equals("revision")
        && parameter.getParameterType() == String.class;
  }

  @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
      Class<? extends HttpMessageConverter<?>> converter) throws IOException {
    int limit = UiLayoutLifecycleRequestDecoder.MAX_REVISION_BODY_BYTES;
    if (input.getHeaders().getContentLength() > limit) throw new BodyTooLarge();
    var media = input.getHeaders().getContentType();
    if (media != null && media.getCharset() != null && !StandardCharsets.UTF_8.equals(media.getCharset())) throw invalid();
    byte[] bytes = input.getBody().readNBytes(limit + 1);
    if (bytes.length > limit) throw new BodyTooLarge();
    try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
    } catch (java.nio.charset.CharacterCodingException malformed) { throw invalid(); }
    var headers = new HttpHeaders();
    headers.putAll(input.getHeaders());
    headers.remove(HttpHeaders.TRANSFER_ENCODING);
    headers.setContentLength(bytes.length);
    return new HttpInputMessage() {
      @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
      @Override public HttpHeaders getHeaders() { return headers; }
    };
  }

  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_REQUEST, "Revision body encoding is invalid.");
  }

  public static final class BodyTooLarge extends RuntimeException {
    public BodyTooLarge() { super("Revision body exceeds its byte budget."); }
  }
}
