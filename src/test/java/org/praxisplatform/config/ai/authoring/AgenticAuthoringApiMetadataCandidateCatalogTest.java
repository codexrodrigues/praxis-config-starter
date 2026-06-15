package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.dto.ApiSearchResult;

@Tag("unit")
class AgenticAuthoringApiMetadataCandidateCatalogTest {

    @Test
    void discoversCandidateFromGovernedMultilingualCatalogText() {
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
                        "procurement suppliers fornecedores compras",
                        "Fornecedores",
                        "Registros de fornecedores usados por fluxos de compras.")));
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

    @Test
    void keepsCatalogGroundedCandidateWhenSemanticRetrievalReturnsDifferentStrongResource() {
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
                        "procurement suppliers fornecedores compras",
                        "Fornecedores",
                        "Registros de fornecedores usados por fluxos de compras.")));
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        ApiSearchResult semanticResult = new ApiSearchResult();
        semanticResult.setPath("/api/human-resources/habilidades");
        semanticResult.setMethod("POST");
        semanticResult.setSummary("Skills with employee capabilities.");
        semanticResult.setSimilarityScore(0.98d);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(semanticResult));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "Crie uma regra para fornecedor bloqueado nao poder ser selecionado em compras",
                "form");

        assertThat(candidates)
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/procurement/suppliers", "/api/human-resources/habilidades");
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
