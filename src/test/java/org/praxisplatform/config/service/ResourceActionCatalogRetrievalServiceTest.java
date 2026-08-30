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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ResourceActionCatalogRetrievalServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesCanonicalActionCatalogWithAuthoringContextHeaders() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> tenants = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> environments = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/actions", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            tenants.add(exchange.getRequestHeaders().getFirst("X-Tenant-ID"));
            users.add(exchange.getRequestHeaders().getFirst("X-User-ID"));
            environments.add(exchange.getRequestHeaders().getFirst("X-Env"));
            writeJson(exchange, 200, """
                    {
                      "resourceKey": "operations.missoes",
                      "resourcePath": "/api/operations/missoes",
                      "group": "operations",
                      "actions": [{
                        "id": "start",
                        "scope": "ITEM",
                        "path": "/api/operations/missoes/{id}/actions/start",
                        "method": "POST",
                        "availability": {"allowed": false, "reason": "resource-context-required"}
                      }]
                    }
                    """);
        });
        server.start();

        ResourceActionCatalogRetrievalService service = new ResourceActionCatalogRetrievalService(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort(),
                5_000L);

        ResourceActionCatalogFetchResult result = service.fetchCatalogResult(
                "operations.missoes",
                null,
                "tenant-a",
                "user-a",
                "local");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCatalog().path("actions").path(0).path("id").asText())
                .isEqualTo("start");
        assertThat(requests).containsExactly("/schemas/actions?resource=operations.missoes");
        assertThat(tenants).containsExactly("tenant-a");
        assertThat(users).containsExactly("user-a");
        assertThat(environments).containsExactly("local");
    }

    @Test
    void appliesHostAuthorizationToCanonicalActionDiscovery() throws Exception {
        List<String> authorizations = new ArrayList<>();
        List<GovernedPlatformRequest> contexts = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/actions", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, """
                    {
                      "resourceKey": "operations.missoes",
                      "resourcePath": "/api/operations/missoes",
                      "actions": []
                    }
                    """);
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        ResourceActionCatalogRetrievalService service = new ResourceActionCatalogRetrievalService(
                new ObjectMapper(),
                baseUrl,
                5_000L,
                context -> {
                    contexts.add(context);
                    return Optional.of("Bearer action-test-token");
                });

        ResourceActionCatalogFetchResult result = service.fetchCatalogResult(
                "operations.missoes",
                baseUrl,
                "tenant-a",
                "admin",
                "local");

        assertThat(result.isSuccess()).isTrue();
        assertThat(authorizations).containsExactly("Bearer action-test-token");
        assertThat(contexts).singleElement().satisfies(context -> {
            assertThat(context.surface())
                    .isEqualTo(GovernedPlatformRequest.Surface.RESOURCE_ACTION_CATALOG);
            assertThat(context.isSameOrigin()).isTrue();
        });
    }

    @Test
    void rejectsNonCanonicalResourceKeysWithoutHttpCall() {
        ResourceActionCatalogRetrievalService service = new ResourceActionCatalogRetrievalService(
                new ObjectMapper(),
                "http://localhost:8088",
                5_000L);

        assertThat(service.fetchCatalogResult(
                        "https://example.com/resources/missions",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceActionCatalogFetchResult.Status.INVALID_RESOURCE);
        assertThat(service.fetchCatalogResult(
                        "operations.missoes?admin=true",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceActionCatalogFetchResult.Status.INVALID_RESOURCE);
    }

    @Test
    void rejectsCatalogWhoseCanonicalResourceDoesNotMatchTheRequest() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/actions", exchange -> writeJson(exchange, 200, """
                {
                  "resourceKey": "operations.outro-recurso",
                  "resourcePath": "/api/operations/outro-recurso",
                  "actions": []
                }
                """));
        server.start();

        ResourceActionCatalogRetrievalService service = new ResourceActionCatalogRetrievalService(
                new ObjectMapper(),
                "http://localhost:" + server.getAddress().getPort(),
                5_000L);

        assertThat(service.fetchCatalogResult(
                        "operations.missoes",
                        null,
                        null,
                        null,
                        null).getStatus())
                .isEqualTo(ResourceActionCatalogFetchResult.Status.INVALID_RESPONSE);
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
