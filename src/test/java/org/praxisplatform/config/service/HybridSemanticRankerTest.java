package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

@Tag("unit")
class HybridSemanticRankerTest {

    @Test
    void shouldImproveHumanSpeechBenchmarkWithoutChangingSemanticRecall() {
        List<BenchmarkCase> corpus = List.of(
                benchmark(
                        "eu queria ver o pessoal que trabalha aqui, sabe, com foto e o código",
                        "/employees",
                        "/benefits",
                        "/departments",
                        "/employees"),
                benchmark(
                        "mostra pra mim quem está inativo e deixa isso bem fácil de enxergar",
                        "/employees",
                        "/departments",
                        "/benefits",
                        "/employees"),
                benchmark(
                        "preciso daquela lista de funcionários, acho que é isso, com os dados deles",
                        "/employees",
                        "/payroll",
                        "/departments",
                        "/employees"),
                benchmark(
                        "onde eu vejo os centros de custo e os departamentos da empresa?",
                        "/departments",
                        "/employees",
                        "/benefits",
                        "/departments"),
                benchmark(
                        "quero consultar a folha e os pagamentos do pessoal",
                        "/payroll",
                        "/employees",
                        "/departments",
                        "/payroll"));

        double vectorMrr = meanReciprocalRank(corpus, false);
        double hybridMrr = meanReciprocalRank(corpus, true);
        double vectorRecall = recallAt(corpus, 3, false);
        double hybridRecall = recallAt(corpus, 3, true);

        assertThat(vectorMrr).isEqualTo(1.0d / 3.0d);
        assertThat(hybridMrr).isEqualTo(2.0d / 3.0d);
        assertThat(hybridRecall).isEqualTo(vectorRecall).isEqualTo(1.0d);
    }

    @Test
    void shouldNeverIntroduceCandidatesOutsideSemanticPoolOrReplaceVectorScore() {
        Document unrelatedLexicalCandidate = document(
                "/outside-vector-pool",
                "funcionários funcionários funcionários",
                0.99d);
        List<Document> semanticPool = List.of(
                document("/benefits", "benefícios corporativos", 0.91d),
                document("/employees", "cadastro de funcionários ativos e inativos", 0.84d));

        List<Document> reranked = HybridSemanticRanker.rerank(
                "quero consultar funcionários inativos",
                semanticPool);

        assertThat(reranked)
                .extracting(Document::getId)
                .containsExactly("/employees", "/benefits")
                .doesNotContain(unrelatedLexicalCandidate.getId());
        assertThat(reranked.get(0).getScore()).isEqualTo(0.84d);
    }

    @Test
    void shouldPreserveVectorOrderWhenThereIsNoLexicalEvidence() {
        List<Document> semanticPool = List.of(
                document("/first", "alpha beta", 0.91d),
                document("/second", "gamma delta", 0.87d));

        assertThat(HybridSemanticRanker.rerank("palavras ausentes", semanticPool))
                .extracting(Document::getId)
                .containsExactly("/first", "/second");
    }

    private static BenchmarkCase benchmark(
            String query,
            String expectedId,
            String first,
            String second,
            String third) {
        Map<String, String> content = Map.of(
                "/employees", "GET /employees cadastro de funcionários colaboradores foto código status ativo inativo",
                "/benefits", "GET /benefits benefícios corporativos vale alimentação plano de saúde",
                "/departments", "GET /departments departamentos centros de custo estrutura da empresa",
                "/payroll", "GET /payroll folha de pagamento salários pagamentos dos funcionários");
        List<Document> vectorRanking = List.of(first, second, third).stream()
                .map(id -> document(id, content.get(id), 0.90d))
                .toList();
        return new BenchmarkCase(query, expectedId, vectorRanking);
    }

    private static double meanReciprocalRank(List<BenchmarkCase> corpus, boolean hybrid) {
        return corpus.stream()
                .mapToDouble(item -> {
                    List<Document> ranking = hybrid
                            ? HybridSemanticRanker.rerank(item.query(), item.vectorRanking())
                            : item.vectorRanking();
                    int rank = rankOf(ranking, item.expectedId());
                    return rank < 0 ? 0.0d : 1.0d / (rank + 1);
                })
                .average()
                .orElse(0.0d);
    }

    private static double recallAt(List<BenchmarkCase> corpus, int k, boolean hybrid) {
        long hits = corpus.stream()
                .filter(item -> {
                    List<Document> ranking = hybrid
                            ? HybridSemanticRanker.rerank(item.query(), item.vectorRanking())
                            : item.vectorRanking();
                    int rank = rankOf(ranking, item.expectedId());
                    return rank >= 0 && rank < k;
                })
                .count();
        return (double) hits / corpus.size();
    }

    private static int rankOf(List<Document> documents, String expectedId) {
        return IntStream.range(0, documents.size())
                .filter(index -> expectedId.equals(documents.get(index).getId()))
                .findFirst()
                .orElse(-1);
    }

    private static Document document(String id, String text, double score) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of("path", id))
                .score(score)
                .build();
    }

    private record BenchmarkCase(String query, String expectedId, List<Document> vectorRanking) {
    }
}
