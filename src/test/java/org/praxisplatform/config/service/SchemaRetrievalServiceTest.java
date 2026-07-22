package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SchemaRetrievalServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchSchemaUsesCanonicalFilteredEndpoint() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            writeJson(exchange, 200, "{\"properties\":{\"field\":{\"type\":\"string\"}}}");
        });
        server.start();

        SchemaRetrievalService service = new SchemaRetrievalService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "schemasBaseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);

        SchemaFetchResult result = service.fetchSchemaResult(
                org.praxisplatform.config.dto.AiSchemaContext.builder()
                        .path("/api/human-resources/vw-perfil-heroi/stats/group-by")
                        .operation("post")
                        .schemaType("response")
                        .build(),
                null);

        assertTrue(result.isSuccess());
        JsonNode schema = result.getSchema();
        assertNotNull(schema);
        assertTrue(schema.path("properties").has("field"));
        assertEquals(1, requests.size());
        assertEquals(
                "/schemas/filtered?path=%2Fapi%2Fhuman-resources%2Fvw-perfil-heroi%2Fstats%2Fgroup-by&operation=post&schemaType=response",
                requests.get(0)
        );
    }

    @Test
    void fetchSchemaForwardsGovernedPrincipalHeaders() throws Exception {
        List<String> tenantHeaders = new ArrayList<>();
        List<String> userHeaders = new ArrayList<>();
        List<String> environmentHeaders = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            tenantHeaders.add(exchange.getRequestHeaders().getFirst("X-Tenant-ID"));
            userHeaders.add(exchange.getRequestHeaders().getFirst("X-User-ID"));
            environmentHeaders.add(exchange.getRequestHeaders().getFirst("X-Env"));
            writeJson(exchange, 200, "{\"x-ui\":{\"analytics\":{\"projections\":[]}}}");
        });
        server.start();

        SchemaRetrievalService service = new SchemaRetrievalService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "schemasBaseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);

        SchemaFetchResult result = service.fetchSchemaResult(
                org.praxisplatform.config.dto.AiSchemaContext.builder()
                        .path("/api/human-resources/vw-analytics-afastamentos/stats/comparison")
                        .operation("post")
                        .schemaType("response")
                        .build(),
                null,
                "tenant-a",
                "user-a",
                "production");

        assertTrue(result.isSuccess());
        assertEquals(List.of("tenant-a"), tenantHeaders);
        assertEquals(List.of("user-a"), userHeaders);
        assertEquals(List.of("production"), environmentHeaders);
    }

    @Test
    void reusesSuccessfulSchemaForTheSameGovernedPrincipal() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            writeJson(exchange, 200, "{\"properties\":{\"departmentIdsIn\":{\"type\":\"array\"}}}");
        });
        server.start();

        SchemaRetrievalService service = new SchemaRetrievalService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "schemasBaseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);
        var context = org.praxisplatform.config.dto.AiSchemaContext.builder()
                .path("/api/human-resources/funcionarios/filter")
                .operation("post")
                .schemaType("response")
                .build();

        SchemaFetchResult first = service.fetchSchemaResult(
                context, null, "tenant-a", "user-a", "local");
        ((com.fasterxml.jackson.databind.node.ObjectNode) first.getSchema().path("properties"))
                .remove("departmentIdsIn");
        SchemaFetchResult second = service.fetchSchemaResult(
                context, null, "tenant-a", "user-a", "local");

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertTrue(second.getSchema().path("properties").has("departmentIdsIn"));
        assertEquals(1, requests.size());
    }

    @Test
    void isolatesSuccessfulSchemaCacheByGovernedPrincipal() throws Exception {
        List<String> tenantHeaders = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            tenantHeaders.add(exchange.getRequestHeaders().getFirst("X-Tenant-ID"));
            writeJson(exchange, 200, "{\"type\":\"object\"}");
        });
        server.start();

        SchemaRetrievalService service = new SchemaRetrievalService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "schemasBaseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);
        var context = org.praxisplatform.config.dto.AiSchemaContext.builder()
                .path("/api/human-resources/funcionarios/filter")
                .operation("post")
                .schemaType("response")
                .build();

        service.fetchSchemaResult(context, null, "tenant-a", "user-a", "local");
        service.fetchSchemaResult(context, null, "tenant-b", "user-a", "local");

        assertEquals(List.of("tenant-a", "tenant-b"), tenantHeaders);
    }

    @Test
    void fetchSchemaUsesHostAuthorizationWithoutAddingItToTheSchemaContext() throws Exception {
        List<String> authorizations = new ArrayList<>();
        List<GovernedPlatformRequest> contexts = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, "{\"type\":\"object\"}");
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        SchemaRetrievalService service = new SchemaRetrievalService(
                new ObjectMapper(),
                context -> {
                    contexts.add(context);
                    return Optional.of("Bearer schema-test-token");
                });
        ReflectionTestUtils.setField(service, "schemasBaseUrl", baseUrl);
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);

        SchemaFetchResult result = service.fetchSchemaResult(
                org.praxisplatform.config.dto.AiSchemaContext.builder()
                        .path("/api/human-resources/vw-analytics-afastamentos/stats/comparison")
                        .operation("post")
                        .schemaType("response")
                        .build(),
                baseUrl,
                "tenant-a",
                "admin",
                "local");

        assertTrue(result.isSuccess());
        assertEquals(List.of("Bearer schema-test-token"), authorizations);
        assertEquals(1, contexts.size());
        assertEquals(GovernedPlatformRequest.Surface.SCHEMA_FILTERED, contexts.get(0).surface());
        assertTrue(contexts.get(0).isSameOrigin());
    }

    @Test
    void fetchSchemaReturnsTypedFailureWithoutCatalogFallbackWhenFilteredFails() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            writeJson(exchange, 400, "{\"error\":\"missing schema\"}");
        });
        server.createContext("/schemas/catalog", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            writeJson(exchange, 200, "{\"endpoints\":[]}");
        });
        server.start();

        SchemaRetrievalService service = new SchemaRetrievalService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "schemasBaseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);

        SchemaFetchResult result = service.fetchSchemaResult(
                org.praxisplatform.config.dto.AiSchemaContext.builder()
                        .path("/api/human-resources/vw-perfil-heroi/stats/group-by")
                        .operation("post")
                        .schemaType("response")
                        .build(),
                null);

        assertFalse(result.isSuccess());
        assertEquals(SchemaFetchResult.Status.BAD_REQUEST, result.getStatus());
        assertEquals("SCHEMA_REQUEST_REJECTED", result.getCode());
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith("/schemas/filtered?"));
    }

    @Test
    void fetchSchemaClassifiesAccessDeniedWithoutCatalogFallback() throws Exception {
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            writeJson(exchange, 403, "{\"error\":\"forbidden\"}");
        });
        server.start();

        SchemaRetrievalService service = new SchemaRetrievalService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "schemasBaseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);

        SchemaFetchResult result = service.fetchSchemaResult(
                org.praxisplatform.config.dto.AiSchemaContext.builder()
                        .path("/api/human-resources/vw-perfil-heroi/stats/group-by")
                        .operation("post")
                        .schemaType("response")
                        .build(),
                null);

        assertFalse(result.isSuccess());
        assertEquals(SchemaFetchResult.Status.FORBIDDEN, result.getStatus());
        assertEquals("SCHEMA_ACCESS_DENIED", result.getCode());
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith("/schemas/filtered?"));
    }

    @Test
    void fetchSchemaClassifiesInvalidJsonPayload() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/schemas/filtered", exchange -> {
            byte[] bytes = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        });
        server.start();

        SchemaRetrievalService service = new SchemaRetrievalService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "schemasBaseUrl", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "timeoutMs", 5_000L);

        SchemaFetchResult result = service.fetchSchemaResult(
                org.praxisplatform.config.dto.AiSchemaContext.builder()
                        .path("/api/human-resources/vw-perfil-heroi/stats/group-by")
                        .operation("post")
                        .schemaType("response")
                        .build(),
                null);

        assertFalse(result.isSuccess());
        assertEquals(SchemaFetchResult.Status.INVALID_RESPONSE, result.getStatus());
        assertEquals("SCHEMA_INVALID_RESPONSE", result.getCode());
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
