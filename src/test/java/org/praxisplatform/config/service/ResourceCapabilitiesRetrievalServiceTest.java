package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ResourceCapabilitiesRetrievalServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesCanonicalResourceCapabilitiesWithAuthoringContextHeaders() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> tenants = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/human-resources/funcionarios/capabilities", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            tenants.add(exchange.getRequestHeaders().getFirst("X-Tenant-ID"));
            writeJson(exchange, 200, """
                    {
                      "resourcePath": "/api/human-resources/funcionarios",
                      "stats": { "fields": [{ "field": "departamento", "groupByEligible": true }] }
                    }
                    """);
        });
        server.start();

        ResourceCapabilitiesRetrievalService service = new ResourceCapabilitiesRetrievalService(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort(),
                5_000L);

        ResourceCapabilitiesFetchResult result = service.fetchCapabilitiesResult(
                "/api/human-resources/funcionarios",
                null,
                "tenant-a",
                "user-a",
                "local");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCapabilities().path("stats").path("fields").path(0).path("field").asText())
                .isEqualTo("departamento");
        assertThat(requests).containsExactly("/api/human-resources/funcionarios/capabilities");
        assertThat(tenants).containsExactly("tenant-a");
    }

    @Test
    void rejectsAbsoluteAndTraversalResourcePathsWithoutHttpCall() {
        ResourceCapabilitiesRetrievalService service = new ResourceCapabilitiesRetrievalService(
                new ObjectMapper(),
                "http://localhost:8088",
                5_000L);

        assertThat(service.fetchCapabilitiesResult(
                        "https://example.com/api/people",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceCapabilitiesFetchResult.Status.INVALID_RESOURCE);
        assertThat(service.fetchCapabilitiesResult(
                        "/api/../internal",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceCapabilitiesFetchResult.Status.INVALID_RESOURCE);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
