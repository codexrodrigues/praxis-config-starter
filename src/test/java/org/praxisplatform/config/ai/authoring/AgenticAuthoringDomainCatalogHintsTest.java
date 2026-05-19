package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringDomainCatalogHintsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enrichDerivesCanonicalResourceKeyFromCollectionPath() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/human-resources/funcionarios",
                "POST",
                "/schemas/human-resources/funcionarios",
                "/api/human-resources/funcionarios",
                "POST",
                0.97,
                "Employee form candidate",
                java.util.List.of("metadata"));

        AgenticAuthoringDomainCatalogHints.enrich(
                contextHints,
                candidate,
                "form",
                "Crie um formulario LGPD para funcionarios",
                null);

        assertThat(contextHints.path("domainCatalog").path("schemaVersion").asText())
                .isEqualTo("praxis.ai.context-hints.domain-catalog/v0.2");
        assertThat(contextHints.path("domainCatalog").path("serviceKey").asText())
                .isEqualTo("praxis-service");
        assertThat(contextHints.path("domainCatalog").path("contextKey").asText())
                .isEqualTo("human-resources");
        assertThat(contextHints.path("domainCatalog").path("resourceKey").asText())
                .isEqualTo("human-resources.funcionarios");
        assertThat(contextHints.path("domainCatalog").path("nodeType").asText())
                .isEqualTo("field");
        assertThat(contextHints.path("domainCatalog").path("intent").asText())
                .isEqualTo("authoring");
        assertThat(contextHints.path("domainCatalog").path("policyProfile").asText())
                .isEqualTo("compliance_review");
        assertThat(contextHints.path("domainCatalog").path("itemTypes").path(0).asText())
                .isEqualTo("node");
        assertThat(contextHints.path("domainCatalog").path("itemTypes").path(1).asText())
                .isEqualTo("governance");
        assertThat(contextHints.path("domainCatalog").path("recommendedAuthoringFlow").isMissingNode())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("recommendedRuleType").isMissingNode())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("relationships").path("enabled").asBoolean())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("relationships").path("federated").asBoolean())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("relationships").path("policyProfile").asText())
                .isEqualTo("compliance_review");
    }

    @Test
    void enrichDerivesCanonicalResourceKeyFromActionPath() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/operations/missoes/{id}/actions/start",
                "POST",
                "/schemas/operations/missoes/start",
                "/api/operations/missoes/{id}/actions/start",
                "POST",
                0.91,
                "Mission action candidate",
                java.util.List.of("metadata"));

        AgenticAuthoringDomainCatalogHints.enrich(
                contextHints,
                candidate,
                "dashboard",
                "Dashboard de status de missoes",
                "mission-service");

        assertThat(contextHints.path("domainCatalog").path("serviceKey").asText())
                .isEqualTo("mission-service");
        assertThat(contextHints.path("domainCatalog").path("contextKey").asText())
                .isEqualTo("operations");
        assertThat(contextHints.path("domainCatalog").path("resourceKey").asText())
                .isEqualTo("operations.missoes");
        assertThat(contextHints.path("domainCatalog").path("nodeType").isMissingNode())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("intent").asText())
                .isEqualTo("authoring");
        assertThat(contextHints.path("domainCatalog").path("policyProfile").asText())
                .isEqualTo("authoring");
        assertThat(contextHints.path("domainCatalog").path("itemTypes").path(0).asText())
                .isEqualTo("node");
        assertThat(contextHints.path("domainCatalog").path("itemTypes").size())
                .isEqualTo(1);
        assertThat(contextHints.path("domainCatalog").path("recommendedAuthoringFlow").isMissingNode())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("query").asText())
                .contains("dashboard de status de missoes")
                .contains("start");
        assertThat(contextHints.path("domainCatalog").path("relationships").path("query").asText())
                .isEqualTo(contextHints.path("domainCatalog").path("query").asText());
        assertThat(contextHints.path("domainCatalog").path("relationships").path("limit").asInt())
                .isEqualTo(8);
    }

    @Test
    void enrichDoesNotInferAuthoringFlowFromSupplierBlockingPromptKeywords() {
        ObjectNode contextHints = objectMapper.createObjectNode();
        AgenticAuthoringCandidate candidate = new AgenticAuthoringCandidate(
                "/api/procurement/suppliers",
                "POST",
                "/schemas/procurement/suppliers",
                "/api/procurement/suppliers",
                "POST",
                0.94,
                "Supplier form candidate",
                java.util.List.of("metadata"));

        AgenticAuthoringDomainCatalogHints.enrich(
                contextHints,
                candidate,
                "form",
                "Crie uma regra para impedir selecao de fornecedores blocked ou inactive em pedidos de compra",
                null);

        assertThat(contextHints.path("domainCatalog").path("recommendedAuthoringFlow").isMissingNode())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("recommendedRuleType").isMissingNode())
                .isTrue();
        assertThat(contextHints.path("domainCatalog").path("resourceKey").asText())
                .isEqualTo("procurement.suppliers");
    }
}
