package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiRagContextService {

    private static final int DEFAULT_MAX_OPTIONS_PER_PATH = 5;
    private static final int DEFAULT_MAX_FIELDS_PER_RESOLVER = 8;

    private final EmbeddingService embeddingService;

    @Value("${praxis.ai.rag.embedding-enabled:false}")
    private boolean embeddingEnabled;

    @Value("${praxis.ai.rag.embedding-candidates:30}")
    private int embeddingCandidates;

    @Value("${praxis.ai.rag.max-actions:5}")
    private int maxActions;

    @Value("${praxis.ai.rag.max-options:5}")
    private int maxOptions;

    @Value("${praxis.ai.rag.max-resolvers:4}")
    private int maxResolvers;

    @Value("${praxis.ai.rag.max-examples:3}")
    private int maxExamples;

    @Value("${praxis.ai.rag.lexical-weight:0.45}")
    private double lexicalWeight;

    @Value("${praxis.ai.rag.embedding-weight:0.55}")
    private double embeddingWeight;

    @Value("${praxis.ai.rag.max-embedding-chars:600}")
    private int maxEmbeddingChars;

    private final ConcurrentMap<String, List<Float>> embeddingCache = new ConcurrentHashMap<>();
    private volatile boolean embeddingUnavailableLogged = false;

    public String buildRagHints(
            String userPrompt,
            JsonNode componentContext,
            JsonNode templateMeta,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "N/A";
        }
        List<String> tokens = tokenize(userPrompt);
        if (tokens.isEmpty()) {
            return "N/A";
        }

        List<RagChunk> actionChunks = buildActionChunks(componentContext);
        List<RagChunk> optionChunks = buildOptionChunks(componentContext);
        List<RagChunk> resolverChunks = buildResolverChunks(componentContext);
        List<RagChunk> exampleChunks = buildExampleChunks(templateMeta);

        List<RagChunk> topActions = pickTop(rankChunks(actionChunks, tokens, embeddingConfig), maxActions);
        List<RagChunk> topOptions = pickTop(rankChunks(optionChunks, tokens, embeddingConfig), maxOptions);
        List<RagChunk> topResolvers = pickTop(rankChunks(resolverChunks, tokens, embeddingConfig), maxResolvers);
        List<RagChunk> topExamples = pickTop(rankChunks(exampleChunks, tokens, embeddingConfig), maxExamples);

        StringBuilder out = new StringBuilder();
        appendSection(out, "ACTIONS", topActions);
        appendSection(out, "OPTIONS", topOptions);
        appendSection(out, "FIELD_RESOLVERS", topResolvers);
        appendSection(out, "EXAMPLES", topExamples);
        if (out.length() == 0) {
            return "N/A";
        }
        return out.toString().trim();
    }

    private List<RagChunk> buildActionChunks(JsonNode componentContext) {
        if (componentContext == null || !componentContext.isObject()) {
            return List.of();
        }
        JsonNode actionsNode = componentContext.get("actionCatalog");
        if (actionsNode == null || !actionsNode.isArray()) {
            return List.of();
        }
        List<RagChunk> chunks = new ArrayList<>();
        for (JsonNode action : actionsNode) {
            if (action == null || action.isNull()) continue;
            String id = textOrNull(action.get("id"));
            if (id == null) continue;
            List<String> keywords = textArray(action.get("keywords"), 8);
            String patchKeys = summarizePatchKeys(action.get("patchTemplate"));
            String label = id;
            if (!keywords.isEmpty()) {
                label += " | keywords: " + String.join(", ", keywords);
            }
            if (patchKeys != null) {
                label += " | patch: " + patchKeys;
            }
            String text = label.toLowerCase(Locale.ROOT);
            chunks.add(new RagChunk("action:" + id, text, label));
        }
        return chunks;
    }

    private List<RagChunk> buildOptionChunks(JsonNode componentContext) {
        if (componentContext == null || !componentContext.isObject()) {
            return List.of();
        }
        JsonNode optionsByPath = componentContext.get("optionsByPath");
        if (optionsByPath == null || !optionsByPath.isObject()) {
            return List.of();
        }
        List<RagChunk> chunks = new ArrayList<>();
        optionsByPath.fields().forEachRemaining(entry -> {
            String path = entry.getKey();
            JsonNode list = entry.getValue();
            if (path == null || list == null || !list.isArray()) {
                return;
            }
            List<String> options = new ArrayList<>();
            int count = 0;
            for (JsonNode opt : list) {
                if (opt == null || opt.isNull()) continue;
                String label = textOrNull(opt.get("label"));
                String value = textOrNull(opt.get("value"));
                if (label == null && value == null) continue;
                String entryText = label != null ? label : value;
                if (label != null && value != null && !label.equals(value)) {
                    entryText = label + " (" + value + ")";
                }
                options.add(entryText);
                count++;
                if (count >= DEFAULT_MAX_OPTIONS_PER_PATH) break;
            }
            if (options.isEmpty()) {
                return;
            }
            String label = path + ": " + String.join(", ", options);
            String text = label.toLowerCase(Locale.ROOT);
            chunks.add(new RagChunk("options:" + path, text, label));
        });
        return chunks;
    }

    private List<RagChunk> buildResolverChunks(JsonNode componentContext) {
        if (componentContext == null || !componentContext.isObject()) {
            return List.of();
        }
        JsonNode resolvers = componentContext.get("fieldResolvers");
        if (resolvers == null || !resolvers.isObject()) {
            return List.of();
        }
        List<RagChunk> chunks = new ArrayList<>();
        resolvers.fields().forEachRemaining(entry -> {
            String path = entry.getKey();
            JsonNode fields = entry.getValue();
            if (path == null || fields == null || !fields.isArray()) {
                return;
            }
            List<String> names = textArray(fields, DEFAULT_MAX_FIELDS_PER_RESOLVER);
            if (names.isEmpty()) {
                return;
            }
            String label = path + ": " + String.join(", ", names);
            String text = label.toLowerCase(Locale.ROOT);
            chunks.add(new RagChunk("resolver:" + path, text, label));
        });
        return chunks;
    }

    private List<RagChunk> buildExampleChunks(JsonNode templateMeta) {
        if (templateMeta == null || templateMeta.isNull()) {
            return List.of();
        }
        List<String> examples = new ArrayList<>();
        JsonNode variants = templateMeta.get("variants");
        if (variants != null && variants.isArray()) {
            for (JsonNode variant : variants) {
                if (variant == null || variant.isNull()) continue;
                JsonNode prompts = variant.get("examplePrompts");
                if (prompts != null && prompts.isArray()) {
                    for (JsonNode prompt : prompts) {
                        String text = textOrNull(prompt);
                        if (text != null) {
                            examples.add(text);
                        }
                    }
                }
            }
        }
        JsonNode rootExamples = templateMeta.get("examplePrompts");
        if (rootExamples != null && rootExamples.isArray()) {
            for (JsonNode prompt : rootExamples) {
                String text = textOrNull(prompt);
                if (text != null) {
                    examples.add(text);
                }
            }
        }
        if (examples.isEmpty()) {
            return List.of();
        }
        List<RagChunk> chunks = new ArrayList<>();
        for (String example : examples) {
            String label = example;
            String text = example.toLowerCase(Locale.ROOT);
            chunks.add(new RagChunk("example:" + hashKey(example), text, label));
        }
        return chunks;
    }

    private List<RagChunk> rankChunks(
            List<RagChunk> chunks,
            List<String> tokens,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        for (RagChunk chunk : chunks) {
            chunk.lexicalScore = lexicalScore(chunk.text, tokens);
            chunk.score = chunk.lexicalScore;
        }
        chunks.sort(Comparator.comparingDouble((RagChunk c) -> c.lexicalScore)
                .reversed()
                .thenComparing(c -> c.id));
        if (embeddingEnabled && embeddingCandidates > 0) {
            rerankWithEmbeddings(chunks, tokens, embeddingConfig);
        }
        chunks.sort(Comparator.comparingDouble((RagChunk c) -> c.score)
                .reversed()
                .thenComparing(c -> c.id));
        return chunks;
    }

    private void rerankWithEmbeddings(
            List<RagChunk> sortedByLexical,
            List<String> tokens,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        int limit = Math.min(embeddingCandidates, sortedByLexical.size());
        if (limit <= 0) return;
        List<RagChunk> candidates = new ArrayList<>(sortedByLexical.subList(0, limit));
        List<Float> queryEmbedding;
        try {
            queryEmbedding = embeddingService.embed(String.join(" ", tokens), embeddingConfig);
        } catch (Exception e) {
            if (!embeddingUnavailableLogged) {
                embeddingUnavailableLogged = true;
                log.warn("[AiRagContextService] Embedding unavailable; using lexical ranking only.", e);
            }
            return;
        }
        for (RagChunk chunk : candidates) {
            List<Float> embedding = resolveChunkEmbedding(chunk, embeddingConfig);
            if (embedding == null) {
                chunk.score = chunk.lexicalScore;
                continue;
            }
            double cosine = cosineSimilarity(queryEmbedding, embedding);
            chunk.score = (lexicalWeight * chunk.lexicalScore) + (embeddingWeight * cosine);
        }
    }

    private List<Float> resolveChunkEmbedding(
            RagChunk chunk,
            EmbeddingService.EmbeddingCallConfig embeddingConfig) {
        String text = truncateForEmbedding(chunk.label);
        String key = hashKey(text);
        List<Float> cached = embeddingCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<Float> embedding = embeddingService.embed(text, embeddingConfig);
            if (embedding != null) {
                embeddingCache.put(key, embedding);
            }
            return embedding;
        } catch (Exception e) {
            if (!embeddingUnavailableLogged) {
                embeddingUnavailableLogged = true;
                log.warn("[AiRagContextService] Failed to embed chunk; using lexical ranking only.", e);
            }
            return null;
        }
    }

    private String truncateForEmbedding(String text) {
        if (text == null) return "";
        String clean = text.trim();
        if (maxEmbeddingChars <= 0 || clean.length() <= maxEmbeddingChars) {
            return clean;
        }
        return clean.substring(0, maxEmbeddingChars);
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0.0d;
        }
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < a.size(); i++) {
            double va = a.get(i) != null ? a.get(i) : 0.0d;
            double vb = b.get(i) != null ? b.get(i) : 0.0d;
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        if (normA == 0.0d || normB == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private void appendSection(StringBuilder out, String title, List<RagChunk> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append("\n");
        }
        out.append(title).append(":\n");
        for (RagChunk item : items) {
            out.append("- ").append(item.label).append("\n");
        }
    }

    private List<RagChunk> pickTop(List<RagChunk> chunks, int limit) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        int size = Math.max(0, limit);
        if (size == 0) {
            return List.of();
        }
        return chunks.subList(0, Math.min(size, chunks.size()));
    }

    private double lexicalScore(String text, List<String> tokens) {
        if (text == null || tokens == null || tokens.isEmpty()) {
            return 0.0d;
        }
        int score = 0;
        for (String token : tokens) {
            if (token.length() < 2) continue;
            if (text.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (part.length() < 2) continue;
            tokens.add(part);
        }
        return new ArrayList<>(tokens);
    }

    private String summarizePatchKeys(JsonNode patchTemplate) {
        if (patchTemplate == null || patchTemplate.isNull()) {
            return null;
        }
        if (!patchTemplate.isObject()) {
            return "value";
        }
        List<String> keys = new ArrayList<>();
        patchTemplate.fieldNames().forEachRemaining(keys::add);
        if (keys.isEmpty()) {
            return null;
        }
        return String.join(", ", keys);
    }

    private List<String> textArray(JsonNode node, int limit) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        int count = 0;
        for (JsonNode item : node) {
            String text = textOrNull(item);
            if (text != null) {
                items.add(text);
                count++;
            }
            if (count >= limit) break;
        }
        return items;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String text = node.asText();
        return text != null && !text.isBlank() ? text : null;
    }

    private String hashKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static final class RagChunk {
        private final String id;
        private final String text;
        private final String label;
        private double lexicalScore;
        private double score;

        private RagChunk(String id, String text, String label) {
            this.id = id;
            this.text = text;
            this.label = label;
        }
    }
}
