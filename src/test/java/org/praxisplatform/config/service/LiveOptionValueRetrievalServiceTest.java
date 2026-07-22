package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@Tag("unit")
class LiveOptionValueRetrievalServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enumeratesAllAuthorizedValuesAfterSemanticFieldScopeWithoutLexicalSearch() throws Exception {
        SchemaRetrievalService schemaRetrievalService = Mockito.mock(SchemaRetrievalService.class);
        when(schemaRetrievalService.fetchSchemaResult(
                any(),
                any(),
                eq("tenant-a"),
                eq("user-a"),
                eq("dev")))
                .thenReturn(SchemaFetchResult.success(filterSchema(true), "/schemas/filtered"));
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<GovernedPlatformRequest> authorizationContext = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/departamentos/option-sources/department/options/filter", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("""
                    {"content":[
                      {"id":16,"label":"Cyberdyne - Inteligência Artificial","extra":null},
                      {"id":17,"label":"Cyberdyne - Engenharia","extra":null},
                      {"id":20,"label":"Capsule Corp - P&D","extra":null}
                    ],"totalPages":1,"totalElements":3}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Data-Version", "Departamento:3");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GovernedPlatformRequestAuthorizationProvider authorizationProvider = context -> {
                authorizationContext.set(context);
                return Optional.of("Bearer governed-token");
            };
            LiveOptionValueRetrievalService service = new LiveOptionValueRetrievalService(
                    objectMapper,
                    schemaRetrievalService,
                    authorizationProvider);
            String baseUrl = "http://localhost:" + server.getAddress().getPort();

            LiveOptionValueRetrievalResult result = service.retrieve(
                    new LiveOptionValueRetrievalRequest(
                            "/api/funcionarios",
                            "departamento",
                            "área organizacional",
                            "in",
                            objectMapper.createArrayNode()
                                    .add("engenharia")
                                    .add("inteligência artificial"),
                            objectMapper.createObjectNode(),
                            100),
                    new AiPrincipalContext("tenant-a", "user-a", "dev", true),
                    baseUrl);

            assertThat(result.valid()).isTrue();
            assertThat(result.canonicalFilterField()).isEqualTo("departamentoIdsIn");
            assertThat(result.optionSourceKey()).isEqualTo("department");
            assertThat(result.datasetVersion()).isEqualTo("Departamento:3");
            assertThat(result.retrievalMode()).isEqualTo("complete_enumeration");
            assertThat(result.exhaustive()).isTrue();
            assertThat(result.candidates())
                    .extracting(LiveOptionValueCandidate::label)
                    .containsExactly(
                            "Cyberdyne - Inteligência Artificial",
                            "Cyberdyne - Engenharia",
                            "Capsule Corp - P&D");
            assertThat(requestBody.get()).isEqualTo("{\"filter\":{}}");
            assertThat(requestBody.get()).doesNotContain("engenharia", "inteligência artificial", "search");
            assertThat(authorization.get()).isEqualTo("Bearer governed-token");
            assertThat(authorizationContext.get().surface())
                    .isEqualTo(GovernedPlatformRequest.Surface.OPTION_SOURCE_VALUES);
            assertThat(authorizationContext.get().isSameOrigin()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClosedWhenOptionSourceIsNotExplicitlyVisibleToAi() {
        SchemaRetrievalService schemaRetrievalService = Mockito.mock(SchemaRetrievalService.class);
        when(schemaRetrievalService.fetchSchemaResult(any(), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(filterSchema(false), "/schemas/filtered"));
        GovernedPlatformRequestAuthorizationProvider authorizationProvider = Mockito.mock(
                GovernedPlatformRequestAuthorizationProvider.class);
        LiveOptionValueRetrievalService service = new LiveOptionValueRetrievalService(
                objectMapper,
                schemaRetrievalService,
                authorizationProvider);

        LiveOptionValueRetrievalResult result = service.retrieve(
                new LiveOptionValueRetrievalRequest(
                        "/api/funcionarios",
                        "departamento",
                        "departamento",
                        "eq",
                        objectMapper.getNodeFactory().textNode("tecnologia"),
                        objectMapper.createObjectNode(),
                        100),
                new AiPrincipalContext("tenant", "user", "dev", true),
                "http://localhost:8088");

        assertThat(result.valid()).isFalse();
        assertThat(result.errorCode()).isEqualTo("option-source-ai-governance-required");
        verifyNoInteractions(authorizationProvider);
    }

    @Test
    void reloadsSelectedIdsThroughCanonicalContextualEndpointInStableOrder() throws Exception {
        SchemaRetrievalService schemaRetrievalService = Mockito.mock(SchemaRetrievalService.class);
        when(schemaRetrievalService.fetchSchemaResult(any(), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(filterSchema(true), "/schemas/filtered"));
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/departamentos/option-sources/department/options/by-ids", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("""
                    [
                      {"id":17,"label":"Cyberdyne - Engenharia","extra":null},
                      {"id":16,"label":"Cyberdyne - Inteligência Artificial","extra":null}
                    ]
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Data-Version", "Departamento:3");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LiveOptionValueRetrievalService service = new LiveOptionValueRetrievalService(
                    objectMapper,
                    schemaRetrievalService,
                    GovernedPlatformRequestAuthorizationProvider.none());
            String baseUrl = "http://localhost:" + server.getAddress().getPort();

            LiveOptionValueRetrievalResult result = service.retrieve(
                    new LiveOptionValueRetrievalRequest(
                            "/api/funcionarios",
                            "departamentoIdsIn",
                            "área organizacional",
                            "in",
                            objectMapper.createArrayNode().add(17).add(16),
                            objectMapper.createObjectNode(),
                            2,
                            true),
                    new AiPrincipalContext("tenant-a", "user-a", "dev", true),
                    baseUrl);

            assertThat(result.valid()).isTrue();
            assertThat(result.retrievalMode()).isEqualTo("selected_ids_reload");
            assertThat(result.fieldResolution()).isEqualTo("canonical_by_ids_confirmation");
            assertThat(result.datasetVersion()).isEqualTo("Departamento:3");
            assertThat(result.candidates())
                    .extracting(candidate -> candidate.id().asInt())
                    .containsExactly(17, 16);
            assertThat(objectMapper.readTree(requestBody.get()))
                    .isEqualTo(objectMapper.readTree("{\"filter\":{},\"ids\":[17,16]}"));
        } finally {
            server.stop(0);
        }
    }

    private JsonNode filterSchema(boolean aiVisible) {
        String governance = aiVisible
                ? "\"x-domain-governance\":{\"aiUsage\":{\"visibility\":\"allow\"}},"
                : "";
        try {
            return objectMapper.readTree("""
                    {
                      "type":"object",
                      "properties":{
                        "cargoIdsIn":{
                          "type":"array",
                          "x-ui":{"label":"Cargos","optionSource":{"key":"jobRole","filterEndpoint":"/api/cargos/options/filter","byIdsEndpoint":"/api/cargos/options/by-ids"}}
                        },
                        "departamentoIdsIn":{
                          "type":"array",
                          "description":"Conjunto de departamentos organizacionais.",
                          %s
                          "x-ui":{"name":"departamentoIdsIn","label":"Departamentos","optionSource":{
                            "key":"department",
                            "filterEndpoint":"/api/departamentos/option-sources/department/options/filter",
                            "byIdsEndpoint":"/api/departamentos/option-sources/department/options/by-ids"
                          }}
                        }
                      }
                    }
                    """.formatted(governance));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
