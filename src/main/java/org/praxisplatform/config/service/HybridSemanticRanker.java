package org.praxisplatform.config.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;

/**
 * Reorders a semantically retrieved candidate pool using generic lexical evidence.
 *
 * <p>The vector search remains the candidate generator and the source of semantic provenance.
 * Lexical scoring cannot add a document, expand scope, or decide the user's intent; it only helps
 * order candidates that already passed the governed vector-store filters.
 */
final class HybridSemanticRanker {

    private static final double BM25_K1 = 1.2d;
    private static final double BM25_B = 0.75d;
    private static final int RRF_K = 60;
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("(?<=[\\p{Ll}\\d])(?=\\p{Lu})");

    private HybridSemanticRanker() {
    }

    static List<Document> rerank(String query, List<Document> vectorRankedDocuments) {
        if (vectorRankedDocuments == null || vectorRankedDocuments.size() < 2) {
            return vectorRankedDocuments == null ? List.of() : List.copyOf(vectorRankedDocuments);
        }
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.copyOf(vectorRankedDocuments);
        }

        List<RankedDocument> ranked = new ArrayList<>(vectorRankedDocuments.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalDocumentLength = 0.0d;
        for (int index = 0; index < vectorRankedDocuments.size(); index++) {
            Document document = vectorRankedDocuments.get(index);
            List<String> terms = tokenize(document != null ? document.getText() : null);
            Map<String, Integer> termFrequency = termFrequency(terms);
            termFrequency.keySet().forEach(term ->
                    documentFrequency.merge(term, 1, Integer::sum));
            totalDocumentLength += terms.size();
            ranked.add(new RankedDocument(document, index, terms.size(), termFrequency));
        }

        double averageDocumentLength = totalDocumentLength / ranked.size();
        for (RankedDocument item : ranked) {
            item.lexicalScore = bm25(
                    queryTerms,
                    item.termFrequency,
                    item.documentLength,
                    averageDocumentLength,
                    documentFrequency,
                    ranked.size());
        }
        List<RankedDocument> lexicalRanked = ranked.stream()
                .filter(item -> item.lexicalScore > 0.0d)
                .sorted(Comparator
                        .comparingDouble((RankedDocument item) -> item.lexicalScore)
                        .reversed()
                        .thenComparingInt(item -> item.vectorRank))
                .toList();
        if (lexicalRanked.isEmpty()) {
            return List.copyOf(vectorRankedDocuments);
        }
        for (int lexicalRank = 0; lexicalRank < lexicalRanked.size(); lexicalRank++) {
            lexicalRanked.get(lexicalRank).lexicalRank = lexicalRank;
        }

        return ranked.stream()
                .sorted(Comparator
                        .comparingDouble(HybridSemanticRanker::reciprocalRankFusionScore)
                        .reversed()
                        .thenComparingInt(item -> item.vectorRank))
                .map(item -> item.document)
                .toList();
    }

    private static double reciprocalRankFusionScore(RankedDocument item) {
        double vectorContribution = 1.0d / (RRF_K + item.vectorRank + 1);
        double lexicalContribution = item.lexicalRank == null
                ? 0.0d
                : 1.0d / (RRF_K + item.lexicalRank + 1);
        return vectorContribution + lexicalContribution;
    }

    private static double bm25(
            List<String> queryTerms,
            Map<String, Integer> termFrequency,
            int documentLength,
            double averageDocumentLength,
            Map<String, Integer> documentFrequency,
            int documentCount) {
        double score = 0.0d;
        for (String queryTerm : queryTerms.stream().distinct().toList()) {
            int frequency = termFrequency.getOrDefault(queryTerm, 0);
            if (frequency == 0) {
                continue;
            }
            int documentsWithTerm = documentFrequency.getOrDefault(queryTerm, 0);
            double inverseDocumentFrequency = Math.log(
                    1.0d + (documentCount - documentsWithTerm + 0.5d) / (documentsWithTerm + 0.5d));
            double lengthNormalization = averageDocumentLength > 0.0d
                    ? documentLength / averageDocumentLength
                    : 1.0d;
            double denominator = frequency + BM25_K1 * (1.0d - BM25_B + BM25_B * lengthNormalization);
            score += inverseDocumentFrequency * (frequency * (BM25_K1 + 1.0d)) / denominator;
        }
        return score;
    }

    private static Map<String, Integer> termFrequency(List<String> terms) {
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        terms.forEach(term -> frequencies.merge(term, 1, Integer::sum));
        return frequencies;
    }

    private static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String expandedCamelCase = CAMEL_CASE_BOUNDARY.matcher(value).replaceAll(" ");
        String normalized = DIACRITICS.matcher(
                        Normalizer.normalize(expandedCamelCase, Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
        return TOKEN_SEPARATOR.splitAsStream(normalized)
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static final class RankedDocument {
        private final Document document;
        private final int vectorRank;
        private final int documentLength;
        private final Map<String, Integer> termFrequency;
        private double lexicalScore;
        private Integer lexicalRank;

        private RankedDocument(
                Document document,
                int vectorRank,
                int documentLength,
                Map<String, Integer> termFrequency) {
            this.document = document;
            this.vectorRank = vectorRank;
            this.documentLength = documentLength;
            this.termFrequency = termFrequency;
        }
    }
}
