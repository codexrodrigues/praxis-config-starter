package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class DomainCatalogPromptContextServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsPromptContextWhenDomainCatalogHintIsPresent() throws Exception {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(ingestionService);

        when(ingestionService.contextLatest(
                eq("praxis-service"),
                eq("tenant-a"),
                eq("dev"),
                eq("node"),
                eq("human-resources"),
                eq("field"),
                eq("salario"),
                eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        new DomainCatalogReleaseResponse(
                                UUID.randomUUID(),
                                "praxis-service:human-resources:latest",
                                "praxis.domain-catalog/v0.1",
                                "praxis-service",
                                "Praxis Service",
                                "test",
                                Instant.parse("2026-04-21T20:00:00Z"),
                                "sha256:test",
                                "tenant-a",
                                "dev",
                                Instant.parse("2026-04-21T20:00:01Z")),
                        "salario",
                        "node",
                        "human-resources",
                        "field",
                        List.of("Use this context as the semantic vocabulary for the requested business scope."),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-service:human-resources:latest",
                                "node",
                                "human-resources.folhas-pagamento.field.salario-liquido",
                                "human-resources",
                                "field",
                                null,
                                null,
                                objectMapper.readTree("""
                                    {
                                      "nodeKey": "human-resources.folhas-pagamento.field.salario-liquido",
                                      "nodeType": "field",
                                      "label": "Salario liquido",
                                      "metadata": {
                                        "fieldName": "salarioLiquido",
                                        "type": "number",
                                        "required": true
                                      }
                                    }
                                    """)))));

        String promptContext = service.buildPromptContext(
                "mostrar salario",
                objectMapper.readTree("""
                    {
                      "domainCatalog": {
                        "serviceKey": "praxis-service",
                        "contextKey": "human-resources",
                        "nodeType": "field",
                        "query": "salario",
                        "limit": 8
                      }
                    }
                    """),
                "tenant-a",
                "dev");

        assertThat(promptContext)
                .contains("DOMAIN_CATALOG_CONTEXT")
                .contains("releaseKey: praxis-service:human-resources:latest")
                .contains("[node/field] Salario liquido")
                .contains("field=salarioLiquido")
                .contains("required=true");
    }

    @Test
    void ignoresRequestsWithoutDomainCatalogHints() {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(ingestionService);

        String promptContext = service.buildPromptContext(
                "mostrar salario",
                objectMapper.createObjectNode().put("resourcePath", "/api/human-resources"),
                "tenant-a",
                "dev");

        assertThat(promptContext).isEmpty();
        verifyNoInteractions(ingestionService);
    }

    @Test
    void acceptsTopLevelDomainCatalogServiceKeyForSimpleClients() {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(ingestionService);

        when(ingestionService.contextLatest(
                eq("praxis-service"),
                eq("tenant-a"),
                eq("dev"),
                eq("node"),
                eq(null),
                eq(null),
                eq("folha"),
                eq(12)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "folha",
                        "node",
                        null,
                        null,
                        List.of(),
                        List.of()));

        String promptContext = service.buildPromptContext(
                "folha",
                objectMapper.createObjectNode().put("domainCatalogServiceKey", "praxis-service"),
                "tenant-a",
                "dev");

        assertThat(promptContext).isEmpty();
        verify(ingestionService).contextLatest(
                "praxis-service",
                "tenant-a",
                "dev",
                "node",
                null,
                null,
                "folha",
                12);
    }
}
