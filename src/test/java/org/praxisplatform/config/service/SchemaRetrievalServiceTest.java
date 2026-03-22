package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
