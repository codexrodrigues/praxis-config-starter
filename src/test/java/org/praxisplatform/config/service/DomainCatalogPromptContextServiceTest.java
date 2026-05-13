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
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyReport;

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
                                      "semanticOwner": "people-ops",
                                      "lifecycle": "active",
                                      "businessGlossary": {
                                        "pt-BR": "Valor recebido pelo colaborador apos descontos"
                                      },
                                      "resolution": {
                                        "strategy": "exact-field-name",
                                        "confidence": "high"
                                      },
                                      "sourceEvidenceKeys": [
                                        "evidence:openapi:folhas-pagamento"
                                      ],
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
                .contains("semanticOwner=people-ops")
                .contains("lifecycle=active")
                .contains("businessGlossary=pt-BR=Valor recebido pelo colaborador apos descontos")
                .contains("resolution=strategy=exact-field-name,confidence=high")
                .contains("sourceEvidenceKeys=evidence:openapi:folhas-pagamento")
                .contains("required=true");
    }

    @Test
    void usesDefaultServiceKeyWhenResourceHintOmitsTechnicalServiceKey() throws Exception {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(ingestionService);

        when(ingestionService.contextLatest(
                eq("praxis-service"),
                eq("procurement.suppliers"),
                eq("tenant-a"),
                eq("dev"),
                eq("node"),
                eq(null),
                eq(null),
                eq("fornecedores"),
                eq(12)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        new DomainCatalogReleaseResponse(
                                UUID.randomUUID(),
                                "praxis-service:procurement.suppliers:latest",
                                "praxis.domain-catalog/v0.1",
                                "praxis-service",
                                "Praxis Service",
                                "test",
                                Instant.parse("2026-04-21T20:00:00Z"),
                                "sha256:test",
                                "tenant-a",
                                "dev",
                                Instant.parse("2026-04-21T20:00:01Z")),
                        "fornecedores",
                        "node",
                        null,
                        null,
                        List.of("Use governed resource context."),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-service:procurement.suppliers:latest",
                                "node",
                                "procurement.suppliers",
                                null,
                                null,
                                null,
                                null,
                                objectMapper.readTree("""
                                    {
                                      "nodeKey": "procurement.suppliers",
                                      "label": "Fornecedores"
                                    }
                                    """)))));

        String promptContext = service.buildPromptContext(
                "fornecedores",
                objectMapper.readTree("""
                    {
                      "domainCatalog": {
                        "resourceKey": "procurement.suppliers"
                      }
                    }
                    """),
                "tenant-a",
                "dev");

        assertThat(promptContext)
                .contains("DOMAIN_CATALOG_CONTEXT")
                .contains("releaseKey: praxis-service:procurement.suppliers:latest")
                .contains("[node/-] Fornecedores");
        verify(ingestionService).contextLatest(
                "praxis-service",
                "procurement.suppliers",
                "tenant-a",
                "dev",
                "node",
                null,
                null,
                "fornecedores",
                12);
    }

    @Test
    void includesAliasItemsInPromptContext() throws Exception {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(ingestionService);

        when(ingestionService.contextLatest(
                eq("praxis-service"),
                eq("tenant-a"),
                eq("dev"),
                eq("alias"),
                eq("human-resources"),
                eq(null),
                eq("liquido"),
                eq(3)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "liquido",
                        "alias",
                        "human-resources",
                        null,
                        List.of(),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-service:human-resources:latest",
                                "alias",
                                "alias:human-resources.folhas-pagamento.field.salario-liquido:liquido",
                                "human-resources",
                                null,
                                null,
                                null,
                                objectMapper.readTree("""
                                    {
                                      "aliasKey": "alias:human-resources.folhas-pagamento.field.salario-liquido:liquido",
                                      "targetKey": "human-resources.folhas-pagamento.field.salario-liquido",
                                      "alias": "liquido",
                                      "aliasType": "business-term"
                                    }
                                    """)))));

        String promptContext = service.buildPromptContext(
                "liquido",
                objectMapper.readTree("""
                    {
                      "domainCatalog": {
                        "serviceKey": "praxis-service",
                        "type": "alias",
                        "contextKey": "human-resources",
                        "query": "liquido",
                        "limit": 3
                      }
                    }
                    """),
                "tenant-a",
                "dev");

        assertThat(promptContext)
                .contains("[alias/-] liquido")
                .contains("alias=liquido")
                .contains("aliasType=business-term");
    }

    @Test
    void includesGovernanceSummaryInPromptContext() throws Exception {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(ingestionService);

        when(ingestionService.contextLatest(
                eq("praxis-service"),
                eq("tenant-a"),
                eq("dev"),
                eq("governance"),
                eq("human-resources"),
                eq(null),
                eq("LGPD"),
                eq(5)))
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
                        "LGPD",
                        "governance",
                        "human-resources",
                        null,
                        List.of("Use governance items to respect privacy, compliance and AI visibility constraints."),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "praxis-service:human-resources:latest",
                                "governance",
                                "governance:human-resources.funcionarios.field.cpf:privacy",
                                null,
                                null,
                                null,
                                null,
                                objectMapper.readTree("""
                                    {
                                      "governanceKey": "governance:human-resources.funcionarios.field.cpf:privacy",
                                      "nodeKey": "human-resources.funcionarios.field.cpf",
                                      "annotationType": "privacy",
                                      "classification": "confidential",
                                      "dataCategory": "personal",
                                      "complianceTags": ["LGPD", "GDPR"],
                                      "aiUsage": {
                                        "visibility": "mask",
                                        "trainingUse": "deny",
                                        "ruleAuthoring": "review_required"
                                      }
                                    }
                                    """)))));

        String promptContext = service.buildPromptContext(
                "quais campos sao LGPD?",
                objectMapper.readTree("""
                    {
                      "domainCatalog": {
                        "serviceKey": "praxis-service",
                        "type": "governance",
                        "contextKey": "human-resources",
                        "query": "LGPD",
                        "limit": 5
                      }
                    }
                    """),
                "tenant-a",
                "dev");

        assertThat(promptContext)
                .contains("[governance/-] governance:human-resources.funcionarios.field.cpf:privacy")
                .contains("classification=confidential")
                .contains("dataCategory=personal")
                .contains("visibility=mask")
                .contains("trainingUse=deny")
                .contains("ruleAuthoring=review_required")
                .contains("complianceTags=LGPD,GDPR");
    }

    @Test
    void includesExplicitRelationshipsWhenRelationshipHintIsPresent() throws Exception {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(ingestionService);

        when(ingestionService.contextLatest(
                eq("praxis-service"),
                eq("tenant-a"),
                eq("dev"),
                eq("node"),
                eq("human-resources"),
                eq("field"),
                eq("centro custo"),
                eq(8)))
                .thenReturn(new DomainCatalogContextResponse(
                        "praxis.domain-catalog-context/v0.1",
                        null,
                        "centro custo",
                        "node",
                        "human-resources",
                        "field",
                        List.of(),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "hr:latest",
                                "node",
                                "human-resources.employee.field.costCenterId",
                                "human-resources",
                                "field",
                                null,
                                null,
                                objectMapper.readTree("""
                                    {
                                      "nodeKey": "human-resources.employee.field.costCenterId",
                                      "nodeType": "field",
                                      "label": "Centro de custo"
                                    }
                                    """)))));
        when(ingestionService.relationshipsLatest(
                eq(null),
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources.employee.field.costCenterId"),
                eq(null),
                eq("references"),
                eq("centro custo"),
                eq(4)))
                .thenReturn(List.of(new DomainCatalogItemResponse(
                        UUID.randomUUID(),
                        "hr:latest",
                        "edge",
                        "edge:hr.employee.references.finance.cost-center",
                        null,
                        null,
                        null,
                        "references",
                        objectMapper.readTree("""
                            {
                              "edgeKey": "edge:hr.employee.references.finance.cost-center",
                              "sourceNodeKey": "human-resources.employee.field.costCenterId",
                              "targetNodeKey": "finance.cost-center",
                              "edgeType": "references",
                              "sourceEvidenceKeys": ["evidence:domain-catalog:hr"]
                            }
                            """))));

        String promptContext = service.buildPromptContext(
                "qual centro de custo?",
                objectMapper.readTree("""
                    {
                      "domainCatalog": {
                        "serviceKey": "praxis-service",
                        "contextKey": "human-resources",
                        "nodeType": "field",
                        "query": "centro custo",
                        "limit": 8,
                        "relationships": {
                          "enabled": true,
                          "federated": true,
                          "sourceNodeKey": "human-resources.employee.field.costCenterId",
                          "edgeType": "references",
                          "query": "centro custo",
                          "limit": 4
                        }
                      }
                    }
                    """),
                "tenant-a",
                "dev");

        assertThat(promptContext)
                .contains("DOMAIN_CATALOG_CONTEXT")
                .contains("[node/field] Centro de custo")
                .contains("DOMAIN_CATALOG_RELATIONSHIPS")
                .contains("federated: true")
                .contains("sourceNodeKey: human-resources.employee.field.costCenterId")
                .contains("[edge/references] edge:hr.employee.references.finance.cost-center")
                .contains("sourceNodeKey=human-resources.employee.field.costCenterId")
                .contains("targetNodeKey=finance.cost-center")
                .contains("sourceEvidenceKeys=evidence:domain-catalog:hr");
    }

    @Test
    void usesFederatedQueryServiceWhenFederatedRelationshipHintIsPresent() throws Exception {
        DomainCatalogIngestionService ingestionService = mock(DomainCatalogIngestionService.class);
        DomainFederationQueryService federationQueryService = mock(DomainFederationQueryService.class);
        DomainCatalogPromptContextService service = new DomainCatalogPromptContextService(
                ingestionService,
                federationQueryService);

        when(federationQueryService.context(
                eq(null),
                eq("human-resources.funcionarios"),
                eq("tenant-a"),
                eq("dev"),
                eq("node"),
                eq("human-resources"),
                eq("field"),
                eq("references"),
                eq("cpf lgpd"),
                eq(12),
                eq(new DomainFederationRetrievalPolicyOptions("compliance_review", null, null, null))))
                .thenReturn(new DomainFederationContextQueryResponse(
                        "praxis.domain-federation-context/v0.1",
                        "tenant-a",
                        "dev",
                        null,
                        "human-resources.funcionarios",
                        "cpf lgpd",
                        "human-resources",
                        "node",
                        "field",
                        "references",
                        12,
                        true,
                        "catalog_projection_fallback",
                        List.of("Use federated context with retrieval policy."),
                        new DomainFederationRetrievalPolicyReport(
                                "compliance_review",
                                0.9d,
                                false,
                                false,
                                2,
                                1,
                                1,
                                0,
                                1,
                                List.of("Excluded denied because aiUsage.visibility=deny.")),
                        new DomainCatalogContextResponse(
                                "praxis.domain-catalog-context/v0.1",
                                null,
                                "cpf lgpd",
                                "node",
                                "human-resources",
                                "field",
                                List.of("Use this context as governed vocabulary."),
                                List.of(new DomainCatalogItemResponse(
                                        UUID.randomUUID(),
                                        "hr:latest",
                                        "node",
                                        "human-resources.funcionarios.field.cpf",
                                        "human-resources",
                                        "field",
                                        null,
                                        null,
                                        objectMapper.readTree("""
                                            {
                                              "label": "CPF",
                                              "metadata": {
                                                "fieldName": "cpf",
                                                "type": "string"
                                              },
                                              "complianceTags": ["LGPD"]
                                            }
                                            """)))),
                                List.of(new DomainCatalogItemResponse(
                                        UUID.randomUUID(),
                                        "hr:latest",
                                        "edge",
                                "edge:hr.funcionario.references.security.usuario",
                                "human-resources",
                                null,
                                null,
                                "references",
                                objectMapper.readTree("""
                                    {
                                      "sourceNodeKey": "human-resources.funcionarios.field.usuarioId",
                                      "targetNodeKey": "security.usuarios.id",
                                      "edgeType": "references"
                                    }
                                    """))),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "federation:active",
                                "contract",
                                "contract:security-user-lookup:v0.1",
                                "security.usuarios",
                                "rest_endpoint",
                                null,
                                null,
                                objectMapper.readTree("""
                                    {
                                      "providerContextKey": "security.usuarios",
                                      "consumerContextKey": "human-resources.funcionarios",
                                      "resourceKey": "security.users"
                                    }
                                    """))),
                        List.of(new DomainCatalogItemResponse(
                                UUID.randomUUID(),
                                "federation:active",
                                "resolution",
                                "resolution:employee-user:v0.1",
                                "human-resources.funcionarios",
                                "same_as",
                                null,
                                null,
                                objectMapper.readTree("""
                                    {
                                      "sourceConceptKey": "funcionario",
                                      "targetConceptKey": "user"
                                    }
                                    """)))));

        String promptContext = service.buildPromptContext(
                "cpf lgpd",
                objectMapper.readTree("""
                    {
                      "domainCatalog": {
                        "serviceKey": "praxis-service",
                        "resourceKey": "human-resources.funcionarios",
                        "contextKey": "human-resources",
                        "nodeType": "field",
                        "query": "cpf lgpd",
                        "policyProfile": "compliance_review",
                        "limit": 12,
                        "relationships": {
                          "enabled": true,
                          "federated": true,
                          "edgeType": "references",
                          "policyProfile": "compliance_review",
                          "limit": 8
                        }
                      }
                    }
                    """),
                "tenant-a",
                "dev");

        assertThat(promptContext)
                .contains("DOMAIN_CATALOG_CONTEXT")
                .contains("[node/field] CPF")
                .contains("DOMAIN_CATALOG_RELATIONSHIPS")
                .contains("federated: true")
                .contains("sourceMode: catalog_projection_fallback")
                .contains("[edge/references] edge:hr.funcionario.references.security.usuario")
                .contains("DOMAIN_FEDERATION_CONTRACTS")
                .contains("contract:security-user-lookup:v0.1")
                .contains("DOMAIN_FEDERATION_RESOLUTIONS")
                .contains("resolution:employee-user:v0.1")
                .contains("DOMAIN_FEDERATION_POLICY")
                .contains("policyProfile: compliance_review")
                .contains("minConfidence: 0.9")
                .contains("Excluded denied because aiUsage.visibility=deny.");
        verifyNoInteractions(ingestionService);
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
