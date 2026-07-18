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
    void usesSemanticRetrievalAsPrimaryEvidenceWhenAvailable() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
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
                .containsExactly("/api/human-resources/habilidades");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval")
                .doesNotContain("lexical-fallback", "weak-evidence");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void scopedSemanticRetrievalDoesNotRetryWithoutTenantScope() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.eq("tenant-local"),
                        Mockito.eq("local"),
                        Mockito.isNull()))
                .thenReturn(List.of());
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(searchResult(
                        "/api/human-resources/funcionarios",
                        "GET",
                        "Funcionarios com cargo, departamento e email.",
                        0.91d)));
        Mockito.when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                        "tenant-local",
                        "local",
                        "default",
                        "v1"))
                .thenReturn(List.of());
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "mostrar dados de colaboradores funcionarios empregados",
                "page",
                "tenant-local",
                "local",
                null);

        assertThat(candidates).isEmpty();
        Mockito.verify(retrievalService).searchApiMetadata(
                Mockito.anyString(),
                Mockito.nullable(String.class),
                Mockito.isNull(),
                Mockito.anyInt(),
                Mockito.isNull(),
                Mockito.eq("tenant-local"),
                Mockito.eq("local"),
                Mockito.isNull());
        Mockito.verify(retrievalService, Mockito.never()).searchApiMetadata(
                Mockito.anyString(),
                Mockito.nullable(String.class),
                Mockito.isNull(),
                Mockito.anyInt(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull());
    }

    @Test
    void keepsExplicitSourceMatchAlongsideSemanticRetrievalWhenUserNamesTheSource() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
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
                "Use a fonte fornecedores",
                "form");

        assertThat(candidates)
                .extracting(AgenticAuthoringCandidate::resourcePath)
                .contains("/api/procurement/suppliers", "/api/human-resources/habilidades");
        assertThat(candidates.stream()
                .filter(candidate -> "/api/procurement/suppliers".equals(candidate.resourcePath()))
                .findFirst()
                .orElseThrow()
                .evidence())
                .contains("explicit-source-match")
                .doesNotContain("weak-evidence");
    }

    @Test
    void ranksSemanticEvidenceAheadOfWeakLexicalComplementsWhenScoresAreInflated() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/operations/vw-resumo-missoes",
                        "POST",
                        "operations resumo missoes acompanhamento time pessoas area detalhes",
                        "Resumo missões",
                        "Visao operacional de missoes por time e area."),
                apiMetadata(
                        "/api/human-resources/vw-analytics-folha-pagamento",
                        "POST",
                        "human resources colaboradores funcionarios pessoas departamento cargo",
                        "Analytics folha pagamento",
                        "Resumo de pessoas, departamentos, cargos e acompanhamento de colaboradores.")));
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        ApiSearchResult semanticResult = new ApiSearchResult();
        semanticResult.setPath("/api/human-resources/vw-analytics-folha-pagamento");
        semanticResult.setMethod("POST");
        semanticResult.setSummary("Analytical HR source for employees, collaborators and department overview.");
        semanticResult.setSimilarityScore(0.50d);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
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
                "dashboard or screen to monitor or track employees collaborators staff name email position department human resources domain",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval")
                .doesNotContain("lexical-fallback", "weak-evidence");
    }

    @Test
    void ranksOperationalResourceAheadOfAnalyticalAndProfileProjectionsForGenericPeoplePrompt() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "GET",
                                "Analytical HR source for employees and payroll metrics.",
                                0.91d),
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao consolidada de pessoas.",
                                0.90d),
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "quero criar algo que mostre informacoes dos colaboradores",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval", "semantic-role:operational-resource");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void keepsCollectionResourceOperationalWhenRetrievedSummaryMentionsAnalytics() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Employee collection usable in analytics dashboards and staff overviews.",
                                "{\"x-ui\":{\"surface\":\"table\",\"analytics\":true}}",
                                0.62d),
                        searchResult(
                                "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                                "POST",
                                "Analytics projection for payroll by department.",
                                0.61d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "staff overview including employee names and departments",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-role:operational-resource")
                .doesNotContain("semantic-role:analytics-projection");
    }

    @Test
    void genericOperationalPromptDemotesDerivedViewsEvenWhenTheyHaveSlightlyHigherSimilarity() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/vw-ranking-reputacao/filter/cursor",
                                "POST",
                                "Operational derived ranking view for people reputation.",
                                0.63d),
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Employee collection with names, e-mails, roles and departments.",
                                0.60d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "quero uma tela para acompanhar o time da empresa",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
    }

    @Test
    void ranksProfileProjectionAheadOfOperationalResourceForProfilePrompt() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.90d),
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao consolidada de pessoas.",
                                0.88d),
                        searchResult(
                                "/api/human-resources/vw-analytics-folha-pagamento",
                                "GET",
                                "Analytical HR source for employees and payroll metrics.",
                                0.87d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "quero uma tela de perfil individual do funcionario",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-perfil-heroi");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval", "semantic-role:profile-projection");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void genericDetailsLaterPromptDoesNotPromoteProfileProjectionAheadOfCollection() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao consolidada de pessoas.",
                                0.90d),
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "quero acompanhar o time, ver pessoas por area e abrir detalhes depois",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-role:operational-resource");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void llmSupportingProfileConceptDoesNotTurnOperationalOverviewIntoProfileNeed() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao consolidada de pessoas.",
                                0.90d),
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: Funcionarios. "
                        + "supporting concepts: Nome, Email, Cargo, Departamento, Perfil 360. "
                        + "desired surface: overview page. "
                        + "semantic query: staff overview page showing employee details such as name, email, position, and department",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-role:operational-resource");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void llmAuthoredBroadSurfaceDoesNotPromoteAuxiliaryProfileOrIndicatorsOverPrimaryEntity() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao consolidada de pessoas.",
                                0.92d),
                        searchResult(
                                "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                                "POST",
                                "Visao analitica de folha e indicadores por colaborador.",
                                0.91d),
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo, departamento e status.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: Funcionarios (colaboradores). "
                        + "supporting concepts: Nome, E-mail, Cargo, Departamento, Status, Perfil 360, Indicadores. "
                        + "desired surface: Tela/Dashboard de acompanhamento de colaboradores com lista, filtros, "
                        + "cartao/perfil 360 e indicadores. "
                        + "semantic query: Tela para acompanhamento de colaboradores mostrando lista de pessoas, "
                        + "dados de perfil, status atual e indicadores de apoio.",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-role:operational-resource");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void llmAuthoredCanonicalResourceFocusUsesGovernedMetadataWithoutEmbeddingRetrieval() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ApiMetadata funcionarios = apiMetadata(
                "/api/human-resources/funcionarios/filter/cursor",
                "POST",
                "human resources funcionarios empregados colaboradores",
                "Funcionarios",
                "Funcionarios com nome, email, cargo e departamento.");
        Mockito.when(repository.findAll()).thenReturn(List.of(funcionarios));
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: human-resources.funcionarios. "
                        + "supporting concepts: empregados, informacoes, nome, cargo, departamento. "
                        + "desired surface: page. "
                        + "semantic query: informacoes dos empregados",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval", "llm-resource-focus", "semantic-role:operational-resource");
        assertThat(candidates.get(0).evidenceBundle().retrievalSource())
                .isEqualTo("semantic_retrieval");
        Mockito.verify(repository).findAll();
        Mockito.verifyNoInteractions(retrievalService);
    }

    @Test
    void llmAuthoredCanonicalResourceFocusKeepsCollectionDashboardOnTheGovernedBusinessEntity() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ApiMetadata funcionarios = apiMetadata(
                "/api/human-resources/funcionarios/filter/cursor",
                "POST",
                "human resources funcionarios empregados colaboradores",
                "Funcionarios",
                "Funcionarios com nome, email, cargo e departamento.");
        Mockito.when(repository.findAll()).thenReturn(List.of(funcionarios));
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: human-resources.funcionarios. "
                        + "supporting concepts: indicadores, filtros, lista detalhada. "
                        + "desired surface: dashboard de colecao com visao geral e tabela. "
                        + "semantic query: tela bonita para acompanhar funcionarios",
                "dashboard");

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.resourcePath()).isEqualTo("/api/human-resources/funcionarios");
            assertThat(candidate.submitUrl()).isEqualTo("/api/human-resources/funcionarios/filter/cursor");
            assertThat(candidate.evidence())
                    .contains("llm-resource-focus", "semantic-role:operational-resource");
        });
        Mockito.verify(repository).findAll();
        Mockito.verifyNoInteractions(retrievalService);
    }

    @Test
    void llmAuthoredCanonicalResourceFocusFallsBackToCatalogScanWhenExactMetadataIsMissing() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of(
                apiMetadata(
                        "/api/human-resources/funcionarios/filter/cursor",
                        "POST",
                        "human resources funcionarios empregados colaboradores",
                        "Funcionarios",
                        "Funcionarios com nome, email, cargo e departamento."),
                apiMetadata(
                        "/api/human-resources/vw-perfil-heroi",
                        "GET",
                        "human resources profile",
                        "Perfil 360",
                        "Perfil individual consolidado de funcionario.")));
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: human-resources.funcionarios. "
                        + "supporting concepts: empregados, informacoes, nome, cargo, departamento. "
                        + "desired surface: page. "
                        + "semantic query: informacoes dos empregados",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval", "llm-resource-focus", "semantic-role:operational-resource");
        Mockito.verify(repository).findAll();
        Mockito.verifyNoInteractions(retrievalService);
    }

    @Test
    void llmAuthoredCanonicalResourceFocusCreatesSchemaPendingCandidateWhenScopedCatalogIsEmpty() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                        "tenant", "local", "default", "v1"))
                .thenReturn(List.of());
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: human-resources.funcionarios. "
                        + "supporting concepts: nome, cargo, departamento. "
                        + "desired surface: tabela para consulta. "
                        + "semantic query: consultar funcionarios",
                "table",
                "tenant",
                "local",
                null);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.resourcePath()).isEqualTo("/api/human-resources/funcionarios");
            assertThat(candidate.submitUrl())
                    .isEqualTo("/api/human-resources/funcionarios/filter/cursor");
            assertThat(candidate.schemaUrl())
                    .contains("schemaType=response");
            assertThat(candidate.evidence())
                    .contains(
                            "domain-discovery-resource-focus",
                            "schema-probe-pending",
                            "semantic-role:operational-resource")
                    .doesNotContain("schema-available", "semantic-retrieval");
            assertThat(candidate.evidenceBundle().retrievalSource()).isEqualTo("context_hint");
        });
        Mockito.verify(repository).findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                "tenant", "local", "default", "v1");
        Mockito.verifyNoInteractions(retrievalService);
    }

    @Test
    void llmAuthoredCanonicalResourceFocusDoesNotBypassSemanticRetrievalForProfileNeed() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.90d),
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao consolidada de pessoas.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: human-resources.funcionarios. "
                        + "supporting concepts: Nome, Email, Cargo, Departamento. "
                        + "desired surface: tela de perfil individual do funcionario. "
                        + "semantic query: ficha de resumo individual com visao consolidada do funcionario",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-perfil-heroi");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval", "semantic-role:profile-projection")
                .doesNotContain("llm-resource-focus");
        Mockito.verifyNoInteractions(repository);
        Mockito.verify(retrievalService).searchApiMetadata(
                Mockito.anyString(),
                Mockito.nullable(String.class),
                Mockito.isNull(),
                Mockito.anyInt(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull());
    }

    @Test
    void llmAuthoredSummaryProfileConceptDoesNotUseOperationalResourceFocusShortcut() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.91d),
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao resumida e consolidada de pessoas.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: human-resources.funcionarios. "
                        + "supporting concepts: resumo, visao, funcionario, dados, informacoes, visao resumida. "
                        + "desired surface: page. "
                        + "semantic query: resumo funcionario",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-perfil-heroi");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval", "semantic-role:profile-projection")
                .doesNotContain("llm-resource-focus");
        Mockito.verifyNoInteractions(repository);
        Mockito.verify(retrievalService).searchApiMetadata(
                Mockito.anyString(),
                Mockito.nullable(String.class),
                Mockito.isNull(),
                Mockito.anyInt(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull());
    }

    @Test
    void llmAuthoredExplicitPayrollEntityKeepsPayrollProjectionAheadOfOperationalResources() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.93d),
                        searchResult(
                                "/api/human-resources/vw-analytics-folha-pagamento/filter/cursor",
                                "POST",
                                "Visao analitica de folha de pagamento com metricas agregadas.",
                                0.89d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: Folha de pagamento. "
                        + "supporting concepts: salario, departamento. "
                        + "desired surface: dashboard analitico de folha de pagamento com metricas agregadas. "
                        + "semantic query: indicadores agregados de remuneracao e pagamento por departamento",
                "dashboard");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-analytics-folha-pagamento");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-role:analytics-projection");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void canonicalizesComparisonOperationAsCapabilityOfItsBaseResource() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(searchResult(
                        "/api/human-resources/funcionarios/stats/comparison",
                        "POST",
                        "Comparacao analitica governada de funcionarios.",
                        0.94d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: Funcionarios. "
                        + "supporting concepts: pagamentos, setores. "
                        + "desired surface: dashboard analitico. "
                        + "semantic query: comparar pagamentos de funcionarios por setor",
                "dashboard");

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.resourcePath()).isEqualTo("/api/human-resources/funcionarios");
            assertThat(candidate.submitUrl())
                    .isEqualTo("/api/human-resources/funcionarios/stats/comparison");
            assertThat(candidate.schemaUrl())
                    .contains("path=/api/human-resources/funcionarios/stats/comparison")
                    .contains("schemaType=response");
            assertThat(candidate.evidence()).contains("semantic-role:analytics-projection");
        });
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void llmDesiredProfileSurfacePromotesProfileProjection() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.90d),
                        searchResult(
                                "/api/human-resources/vw-perfil-heroi",
                                "GET",
                                "Perfil 360 com visao consolidada de pessoas.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "primary business entity: Funcionarios. "
                        + "supporting concepts: Nome, Email, Cargo, Departamento. "
                        + "desired surface: tela de perfil individual do funcionario. "
                        + "semantic query: ficha de resumo individual com visao consolidada do funcionario",
                "page");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/vw-perfil-heroi");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-role:profile-projection");
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void keepsEmployeeSubjectAheadOfUnrelatedAnalyticsRoleForMetricPrompt() {
        ApiMetadataRepository repository = Mockito.mock(ApiMetadataRepository.class);
        Mockito.when(repository.findAll()).thenReturn(List.of());
        ContextRetrievalService retrievalService = Mockito.mock(ContextRetrievalService.class);
        Mockito.when(retrievalService.searchApiMetadata(
                        Mockito.anyString(),
                        Mockito.nullable(String.class),
                        Mockito.isNull(),
                        Mockito.anyInt(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull()))
                .thenReturn(List.of(
                        searchResult(
                                "/api/human-resources/funcionarios/filter/cursor",
                                "POST",
                                "Funcionarios com nome, email, cargo e departamento.",
                                0.91d),
                        searchResult(
                                "/api/human-resources/vw-analytics-folha-pagamento/stats/group-by",
                                "POST",
                                "x-ui analytics payroll metrics by department.",
                                0.88d)));
        AgenticAuthoringApiMetadataCandidateCatalog catalog =
                new AgenticAuthoringApiMetadataCandidateCatalog(repository, retrievalService);

        List<AgenticAuthoringCandidate> candidates = catalog.discover(
                "monte um grafico com indicadores de funcionarios por departamento",
                "dashboard");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.get(0).resourcePath())
                .isEqualTo("/api/human-resources/funcionarios");
        assertThat(candidates.get(0).evidence())
                .contains("semantic-retrieval", "semantic-role:operational-resource");
    }

    private ApiMetadata apiMetadata(
            String path,
            String method,
            String tags,
            String summary,
            String description) {
        return new ApiMetadata(path, method, tags, summary, description, null, null, null, "[]", "{}", null);
    }

    private ApiSearchResult searchResult(String path, String method, String summary, double score) {
        ApiSearchResult result = new ApiSearchResult();
        result.setPath(path);
        result.setMethod(method);
        result.setSummary(summary);
        result.setSimilarityScore(score);
        return result;
    }

    private ApiSearchResult searchResult(
            String path,
            String method,
            String summary,
            String responseSchema,
            double score) {
        ApiSearchResult result = searchResult(path, method, summary, score);
        result.setResponseSchema(responseSchema);
        return result;
    }
}
