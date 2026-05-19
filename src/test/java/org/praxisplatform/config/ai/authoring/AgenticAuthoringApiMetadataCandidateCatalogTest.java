package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

@Tag("unit")
class AgenticAuthoringApiMetadataCandidateCatalogTest {

    @Test
    void discoversEnglishProcurementSuppliersFromPortugueseGovernedRulePrompt() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/human-resources/habilidades",
                        "POST",
                        "human resources skills",
                        "Skills",
                        "Employee skill records."),
                apiMetadata(
                        "/api/procurement/suppliers",
                        "POST",
                        "procurement suppliers",
                        "Suppliers",
                        "Supplier records used by purchase flows.")));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                "form");

        assertThat(candidates)
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/procurement/suppliers")
                .doesNotContain("/api/human-resources/habilidades");
    }

    private ApiMetadata apiMetadata(
            String path,
            String method,
            String tags,
            String summary,
            String description) {
        return new ApiMetadata(path, method, tags, summary, description, null, null, null, "[]", "{}", null);
    }
}
