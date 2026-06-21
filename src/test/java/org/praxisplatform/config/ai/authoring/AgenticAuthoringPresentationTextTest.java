package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringPresentationTextTest {

    @Test
    void displayNormalizesCommonPortugueseBusinessTermsWithoutChangingTechnicalKeys() {
        assertThat(AgenticAuthoringPresentationText.display(
                "analytics folha pagamento: boa para analises, indicadores e graficos"))
                .isEqualTo("analytics folha pagamento: boa para análises, indicadores e gráficos");
        assertThat(AgenticAuthoringPresentationText.display(
                "Campos confirmados: Codigo, Responsavel Nome. Filtrar com paginacao por cursor."))
                .isEqualTo("Campos confirmados: Código, Responsável Nome. Filtrar com paginação por cursor.");
        assertThat(AgenticAuthoringPresentationText.display(
                "Pre-visualizacao pronta. Materializacao, Validacao e Proximo passo da decisao semantica."))
                .isEqualTo("Pré-visualização pronta. Materialização, Validação e Próximo passo da decisão semântica.");
        assertThat(AgenticAuthoringPresentationText.display("Suppliers"))
                .isEqualTo("Suppliers");
    }

    @Test
    void titleCaseTurnsFallbackResourceKeysIntoPublicLabels() {
        assertThat(AgenticAuthoringPresentationText.titleCase("analytics folha pagamento"))
                .isEqualTo("Analytics Folha Pagamento");
        assertThat(AgenticAuthoringPresentationText.titleCase("acordos regulatorios"))
                .isEqualTo("Acordos Regulatorios");
    }

    @Test
    void assistantReplyRemovesRuntimeInternalTermsFromUserFacingText() {
        String message = """
                Disponíveis aqui:
                Participantes da missão — superfície relacionada a registro (record.related-surface) que abre a tabela de participantes da missão (target.resourcePath: operations/missao-participantes).
                Linha do tempo da missão — superfície relacionada a registro que abre a tabela de eventos da missão (target.resourcePath: operations/missao-eventos).
                Obter resumo de missão (Detail) — superfície de visualização por item (VIEW, scope ITEM) que fornece um painel resumido para uma missão (resourcePath: operations/vw-resumo-missoes).
                Observações relevantes: canonicalOperations.create = false.
                """;

        String publicMessage = AgenticAuthoringPresentationText.assistantReply(message);

        assertThat(publicMessage)
                .contains("Participantes da missão")
                .contains("Linha do tempo da missão")
                .contains("eventos da missão")
                .contains("visualização por registro")
                .contains("criação direta não publicada nesta visão")
                .doesNotContain("target.resourcePath")
                .doesNotContain("resourcePath")
                .doesNotContain("record.related-surface")
                .doesNotContain("canonicalOperations")
                .doesNotContain("operations/missao");
    }

    @Test
    void assistantReplyDoesNotTreatHumanSlashPhrasesAsTechnicalPaths() {
        String message = """
                Capacidades relevantes para gráficas/estatísticas:
                O que você pode criar/abrir aqui:
                distribuições, agrupamentos, série temporal e métricas para dashboards.
                Use /api/human-resources/funcionarios apenas como referência técnica.
                """;

        String publicMessage = AgenticAuthoringPresentationText.assistantReply(message);

        assertThat(publicMessage)
                .contains("gráficas/estatísticas")
                .contains("criar/abrir")
                .contains("métricas")
                .contains("fonte confirmada")
                .doesNotContain("gráfonte confirmada")
                .doesNotContain("méfonte confirmada")
                .doesNotContain("pode fonte confirmada");
    }

    @Test
    void assistantReplyTurnsResourcePathEvidenceIntoReadableSourceStatement() {
        String message = """
                Campos disponíveis:
                resourcePath: operations/missao-eventos — fonte deste conjunto de dados.
                """;

        String publicMessage = AgenticAuthoringPresentationText.assistantReply(message);

        assertThat(publicMessage)
                .contains("fonte governada confirmada pelo catálogo")
                .contains("fonte deste conjunto de dados")
                .doesNotContain("fonte: fonte confirmada")
                .doesNotContain("resourcePath")
                .doesNotContain("operations/missao-eventos");
    }

    @Test
    void assistantReplyRemovesTableAssistantCatalogInternalsFromConsultativeAnswers() {
        String message = """
                Você pode criar (ou abrir) as seguintes tabelas/surfaces a partir deste contexto:

                Participantes da missão — tabela relacionada (Participantes da missão). Fonte: surface declarado; destino como tabela (praxis-table) consultando operations/missao-participantes.
                Linha do tempo da missão — tabela relacionada (Linha do tempo da missão). Destino como tabela (praxis-table) consultando operations/missao-eventos.
                Obter resumo de missão (Detail) — painel/visualização por item (resource-surface, intenção: detail) que retorna um resumo agregado da missão. Recurso associado: operations/vw-resumo-missoes.

                As duas primeiras são surfaces relacionadas por registro e usam componente do tipo tabela (praxis-table) como target.
                A surface "detail" é uma visão por item. Escopo: registro (ITEM). Escolha qual superfície abrir.
                Você pode criar/abrir as seguintes "tabelas" / superfícies baseadas no contexto atual (itens declarados em recordSurfaces).
                Label: "Participantes da missão" (superfície relacionada a registro).
                Tipo: componente tabela (tabela) como visão relacionada alvo.
                ResourcePath alvo: operations/missao-participantes.
                Semantic intent: Detail.
                ResourcePath / operação: operations/vw-resumo-missoes.
                O sistema só pode materializar superfícies que existam no consultativeContext.
                Tabelas e visões relacionadas por visão (record-related visões relacionadas).
                Participantes da missão — visão relacionada (scope: ITEM).
                Tabelas com agrupamentos/estatísticas (statsGroupBy, statsDistribution, statsTimeSeries), optionSources e cursor/client.
                Criação (create) e exclusão (delete) não estão habilitadas.
                visões relacionadas/tabelas relacionáveis já declaradas.
                Resumo da missão (VIEW por item). criação = false. layout (autor). abrir/abranger.
                """;

        String publicMessage = AgenticAuthoringPresentationText.assistantReply(message);

        assertThat(publicMessage)
                .contains("Participantes da missão")
                .contains("Linha do tempo da missão")
                .contains("Resumo da missão")
                .contains("visões relacionadas")
                .contains("destino")
                .contains("Escopo: registro")
                .doesNotContain("surfaces")
                .doesNotContain("surface")
                .doesNotContain("resource-surface")
                .doesNotContain("praxis-table")
                .doesNotContain("target")
                .doesNotContain("recordSurfaces")
                .doesNotContain("consultativeContext")
                .doesNotContain("ResourcePath")
                .doesNotContain("Semantic intent")
                .doesNotContain("Label:")
                .doesNotContain("superfícies")
                .doesNotContain("superfície")
                .doesNotContain("record-related")
                .doesNotContain("scope: ITEM")
                .doesNotContain("statsGroupBy")
                .doesNotContain("statsDistribution")
                .doesNotContain("statsTimeSeries")
                .doesNotContain("optionSources")
                .doesNotContain("cursor/client")
                .doesNotContain("create")
                .doesNotContain("delete")
                .doesNotContain("VIEW por item")
                .doesNotContain("criação = false")
                .doesNotContain("layout (autor)")
                .doesNotContain("abrir/abranger")
                .doesNotContain("(ITEM)")
                .doesNotContain("qual superfície")
                .doesNotContain("operations/");
    }
}
