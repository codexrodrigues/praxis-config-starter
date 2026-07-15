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
class ResourceSurfaceCatalogRetrievalServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesCanonicalSurfaceCatalogWithAuthoringContextHeaders() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> tenants = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> environments = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/surfaces", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            tenants.add(exchange.getRequestHeaders().getFirst("X-Tenant-ID"));
            users.add(exchange.getRequestHeaders().getFirst("X-User-ID"));
            environments.add(exchange.getRequestHeaders().getFirst("X-Env"));
            writeJson(exchange, 200, """
                    {
                      "resourceKey": "human-resources.funcionarios",
                      "resourcePath": "/api/human-resources/funcionarios",
                      "group": "human-resources",
                      "surfaces": [{ "id": "hero-profile", "scope": "ITEM" }]
                    }
                    """);
        });
        server.start();

        ResourceSurfaceCatalogRetrievalService service = new ResourceSurfaceCatalogRetrievalService(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort(),
                5_000L);

        ResourceSurfaceCatalogFetchResult result = service.fetchCatalogResult(
                "human-resources.funcionarios",
                null,
                "tenant-a",
                "user-a",
                "local");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCatalog().path("resourcePath").asText())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(requests)
                .containsExactly("/schemas/surfaces?resource=human-resources.funcionarios");
        assertThat(tenants).containsExactly("tenant-a");
        assertThat(users).containsExactly("user-a");
        assertThat(environments).containsExactly("local");
    }

    @Test
    void rejectsNonCanonicalResourceKeysWithoutHttpCall() {
        ResourceSurfaceCatalogRetrievalService service = new ResourceSurfaceCatalogRetrievalService(
                new ObjectMapper(),
                "http://localhost:8088",
                5_000L);

        assertThat(service.fetchCatalogResult(
                        "https://example.com/resources/people",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceSurfaceCatalogFetchResult.Status.INVALID_RESOURCE);
        assertThat(service.fetchCatalogResult(
                        "human-resources.funcionarios?admin=true",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceSurfaceCatalogFetchResult.Status.INVALID_RESOURCE);
    }

    @Test
    void rejectsCatalogWhoseCanonicalResourceDoesNotMatchTheRequest() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/surfaces", exchange -> writeJson(exchange, 200, """
                {
                  "resourceKey": "human-resources.outro-recurso",
                  "resourcePath": "/api/human-resources/outro-recurso",
                  "surfaces": []
                }
                """));
        server.start();

        ResourceSurfaceCatalogRetrievalService service = new ResourceSurfaceCatalogRetrievalService(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort(),
                5_000L);

        assertThat(service.fetchCatalogResult(
                        "human-resources.funcionarios",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceSurfaceCatalogFetchResult.Status.INVALID_RESPONSE);
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
