package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;

@Tag("unit")
class Domain360CatalogServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregatesFederatedDomainItemsInto360Catalog() throws Exception {
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        Domain360CatalogService service = new Domain360CatalogService(queryService);
        DomainCatalogReleaseResponse release = new DomainCatalogReleaseResponse(
                UUID.randomUUID(),
                "domain-catalog:hr:v1",
                "praxis.domain-catalog/v0.2",
                "praxis-service",
                "Praxis Service",
                "test",
                Instant.parse("2026-05-31T10:00:00Z"),
                "sha256:test",
                "tenant-a",
                "dev",
                Instant.parse("2026-05-31T10:00:01Z"));
        DomainCatalogContextResponse context = new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                release,
                "funcionarios",
                null,
                "human-resources",
                null,
                List.of("Use this context."),
                List.of(
                        item("node", "human-resources.funcionarios", "human-resources", "concept", """
                                {
                                  "nodeKey": "human-resources.funcionarios",
                                  "nodeType": "concept",
                                  "source": "api-resource",
                                  "label": "Funcionarios",
                                  "description": "Cadastro e visao operacional dos funcionarios",
                                  "metadata": {
                                    "resourceKey": "human-resources.funcionarios"
                                  }
                                }
                                """),
                        item("node", "human-resources.funcionarios.field.departamentoId", "human-resources", "field", """
                                {
                                  "nodeKey": "human-resources.funcionarios.field.departamentoId",
                                  "nodeType": "field",
                                  "label": "Departamento",
                                  "metadata": {
                                    "fieldName": "departamentoId",
                                    "type": "number"
                                  }
                                }
                                """),
                        item("node", "human-resources.funcionarios.surface.profile", "human-resources", "surface", """
                                {
                                  "surfaceKey": "funcionario-profile",
                                  "nodeType": "surface",
                                  "label": "Perfil 360"
                                }
                                """),
                        item("node", "human-resources.funcionarios.stats.group-by", "human-resources", "stats", """
                                {
                                  "nodeKey": "human-resources.funcionarios.stats.group-by",
                                  "nodeType": "stats",
                                  "label": "Agrupamentos de funcionarios"
                                }
                                """)));
        DomainCatalogItemResponse optionSource = item("contract", "contract:cargos-options", "human-resources", "lookup_option_source", """
                {
                  "contract": {
                    "contractKey": "contract:cargos-options",
                    "contractType": "lookup_option_source",
                    "operationKey": "cargos.options",
                    "resourceKey": "human-resources.cargos"
                  }
                }
                """);
        DomainCatalogItemResponse relationship = item("edge", "funcionario-departamento", "human-resources", null, """
                {
                  "sourceContextKey": "funcionarios",
                  "targetContextKey": "departamentos",
                  "contract": {
                    "contractKey": "funcionario-departamento",
                    "contractType": "many-to-one"
                  }
                }
                """);
        when(queryService.context(
                eq("praxis-service"),
                eq("human-resources.funcionarios"),
                eq("tenant-a"),
                eq("dev"),
                eq(null),
                eq("human-resources"),
                eq(null),
                eq(null),
                nullable(String.class),
                eq(100),
                eq(new DomainFederationRetrievalPolicyOptions("authoring", null, null, null))))
                .thenReturn(new DomainFederationContextQueryResponse(
                        "praxis.domain-federation-context/v0.1",
                        "tenant-a",
                        "dev",
                        "praxis-service",
                        "human-resources.funcionarios",
                        "funcionarios",
                        "human-resources",
                        null,
                        null,
                        null,
                        100,
                        true,
                        "catalog_projection_fallback",
                        List.of("Use this context."),
                        null,
                        context,
                        List.of(relationship),
                        List.of(optionSource),
                        List.of()));

        var response = service.catalog(
                "praxis-service",
                "human-resources.funcionarios",
                "tenant-a",
                "dev",
                "human-resources",
                "funcionarios",
                100);

        assertThat(response.schemaVersion()).isEqualTo("praxis.domain-360-catalog/v0.1");
        assertThat(response.coverage().resourceCount()).isEqualTo(1);
        assertThat(response.coverage().fieldCount()).isEqualTo(1);
        assertThat(response.coverage().surfaceCount()).isEqualTo(1);
        assertThat(response.coverage().statsCount()).isEqualTo(1);
        assertThat(response.coverage().optionSourceCount()).isEqualTo(1);
        assertThat(response.coverage().relationshipCount()).isEqualTo(1);
        assertThat(response.recommendedRoutes()).extracting("routeKey")
                .contains("dashboard-overview", "rich-list", "detail-surface");
        assertThat(response.diagnostics()).extracting("code")
                .doesNotContain("domain360.empty-context", "domain360.no-fields", "domain360.no-surfaces");
        verify(queryService).context(
                eq("praxis-service"),
                eq("human-resources.funcionarios"),
                eq("tenant-a"),
                eq("dev"),
                eq(null),
                eq("human-resources"),
                eq(null),
                eq(null),
                eq("funcionarios"),
                eq(100),
                eq(new DomainFederationRetrievalPolicyOptions("authoring", null, null, null)));
        verifyNoMoreInteractions(queryService);
    }

    @Test
    void reportsDiagnosticsWhenDomainScopeIsEmpty() {
        DomainFederationQueryService queryService = mock(DomainFederationQueryService.class);
        Domain360CatalogService service = new Domain360CatalogService(queryService);
        when(queryService.context(
                eq(null),
                eq("risk-intelligence.incidentes"),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                nullable(String.class),
                eq(20),
                eq(new DomainFederationRetrievalPolicyOptions("authoring", null, null, null))))
                .thenReturn(new DomainFederationContextQueryResponse(
                        "praxis.domain-federation-context/v0.1",
                        null,
                        null,
                        null,
                        "risk-intelligence.incidentes",
                        null,
                        null,
                        null,
                        null,
                        null,
                        20,
                        false,
                        "catalog_projection_fallback",
                        List.of(),
                        null,
                        new DomainCatalogContextResponse(
                                "praxis.domain-catalog-context/v0.1",
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                List.of()),
                        List.of(),
                        List.of(),
                        List.of()));

        var response = service.catalog(null, "risk-intelligence.incidentes", null, null, null, null, 20);

        assertThat(response.coverage().resourceCount()).isEqualTo(1);
        assertThat(response.resources()).first()
                .extracting("key", "kind")
                .containsExactly("risk-intelligence.incidentes", "resource");
        assertThat(response.recommendedRoutes()).isEmpty();
        assertThat(response.diagnostics()).extracting("code")
                .contains("domain360.empty-context", "domain360.no-fields", "domain360.no-surfaces")
                .doesNotContain("domain360.resource-not-explicit");
    }

    private DomainCatalogItemResponse item(
            String itemType,
            String itemKey,
            String contextKey,
            String nodeType,
            String payload) throws Exception {
        return new DomainCatalogItemResponse(
                UUID.randomUUID(),
                "domain-catalog:hr:v1",
                itemType,
                itemKey,
                contextKey,
                nodeType,
                null,
                "edge".equals(itemType) ? "depends_on" : null,
                objectMapper.readTree(payload));
    }
}
