package org.praxisplatform.config.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

@Tag("unit")
class ConfigGlobalExceptionHandlerTest {

    private final ConfigGlobalExceptionHandler handler = new ConfigGlobalExceptionHandler();

    @Test
    void annotatedWithHighestPrecedenceForConfigControllers() {
        Order order = ConfigGlobalExceptionHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void mapsHttpMessageNotReadableExceptionToCanonical400Payload() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character",
                new MockHttpInputMessage("{bad".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("status", 400)
                .containsEntry("code", "REQUEST_PAYLOAD_INVALID")
                .containsEntry("error", "Bad Request");
    }

    @Test
    void mapsIllegalArgumentExceptionTo400BadRequest() {
        ResponseEntity<Object> response = handler.handleIllegalArgument(
                new IllegalArgumentException("releaseKey invalida"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("status", 400)
                .containsEntry("error", "Bad Request")
                .containsEntry("message", "releaseKey invalida");
    }
}
