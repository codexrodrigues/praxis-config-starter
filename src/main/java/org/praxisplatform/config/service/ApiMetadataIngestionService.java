package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiMetadataIngestionService {

    private final ApiMetadataRepository repository;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;

    @Transactional
    public void ingestCatalog(String catalogJson) {
        try {
            JsonNode root = objectMapper.readTree(catalogJson);
            JsonNode endpoints = root.path("endpoints");
            if (endpoints.isMissingNode() || !endpoints.isArray()) {
                log.warn("No endpoints array found in catalog JSON");
                return;
            }

            for (JsonNode ep : endpoints) {
                String path = ep.path("path").asText(null);
                String method = ep.path("method").asText(null);
                if (path == null || method == null) {
                    log.warn("Skipping endpoint without path/method: {}", ep);
                    continue;
                }

                String tags = toCommaSeparated(ep.path("tags"));
                String summary = ep.path("summary").asText(null);
                String description = ep.path("description").asText(null);
                String operationId = ep.path("operationId").asText(null);

                String requestSchema = safeWrite(ep.path("requestSchema"));
                String responseSchema = safeWrite(ep.path("responseSchema"));
                String parameters = safeWrite(ep.path("parameters"));
                String rawJson = safeWrite(ep);

                String embeddingSummary = buildSummary(path, method, tags, summary, description, operationId, ep);
                List<Float> embedding = embeddingService.embed(embeddingSummary);

                ApiMetadata meta = upsert(path, method, tags, summary, description, operationId,
                        requestSchema, responseSchema, parameters, rawJson, embedding);

                log.info("Ingested api metadata: {} {}", meta.getMethod(), meta.getPath());
            }
        } catch (Exception e) {
            log.error("Error ingesting API catalog", e);
            throw new RuntimeException("Failed to ingest API catalog", e);
        }
    }

    private ApiMetadata upsert(String path, String method, String tags, String summary, String description,
                               String operationId, String requestSchema, String responseSchema, String parameters,
                               String rawJson, List<Float> embedding) {
        Optional<ApiMetadata> existing = repository.findByPathAndMethod(path, method);
        ApiMetadata meta = existing.orElse(new ApiMetadata());
        meta.setPath(path);
        meta.setMethod(method);
        meta.setTags(tags);
        meta.setSummary(summary);
        meta.setDescription(description);
        meta.setOperationId(operationId);
        meta.setRequestSchema(requestSchema);
        meta.setResponseSchema(responseSchema);
        meta.setParameters(parameters);
        meta.setRawJson(rawJson);
        meta.setEmbedding(embedding);
        return repository.save(meta);
    }

    private String buildSummary(String path, String method, String tags, String summary, String description,
                                String operationId, JsonNode ep) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add(method + " " + path);
        if (summary != null) joiner.add("Summary: " + summary);
        if (description != null) joiner.add("Desc: " + description);
        if (operationId != null) joiner.add("OpId: " + operationId);
        if (tags != null && !tags.isBlank()) joiner.add("Tags: " + tags);
        joiner.add("Params: " + summarizeParams(ep.path("parameters")));
        joiner.add("Req: " + summarizeSchema(ep.path("requestSchema")));
        joiner.add("Res: " + summarizeSchema(ep.path("responseSchema")));
        joiner.add("ReqFields: " + summarizeFields(ep.path("requestSchema").path("fields")));
        joiner.add("ResFields: " + summarizeFields(ep.path("responseSchema").path("fields")));
        joiner.add("ReqRelations: " + summarizeRelations(ep.path("requestSchema").path("relations")));
        joiner.add("ResRelations: " + summarizeRelations(ep.path("responseSchema").path("relations")));
        return joiner.toString();
    }

    private String summarizeFields(JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) return "none";
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode f : fieldsNode) {
            String name = f.path("name").asText("");
            String type = f.path("type").asText("");
            boolean required = f.path("required").asBoolean(false);
            joiner.add(name + ":" + type + (required ? " (req)" : ""));
        }
        String res = joiner.toString();
        return res.isEmpty() ? "none" : res;
    }

    private String summarizeRelations(JsonNode relNode) {
        if (relNode == null || !relNode.isArray()) return "none";
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode r : relNode) {
            String field = r.path("field").asText("");
            String target = r.path("targetSchema").asText("");
            String card = r.path("cardinality").asText("");
            joiner.add(field + "->" + target + (card.isEmpty() ? "" : " (" + card + ")"));
        }
        String res = joiner.toString();
        return res.isEmpty() ? "none" : res;
    }

    private String summarizeParams(JsonNode params) {
        if (params == null || !params.isArray()) return "none";
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode p : params) {
            String name = p.path("name").asText("");
            String in = p.path("in").asText("");
            String type = p.path("type").asText("");
            boolean required = p.path("required").asBoolean(false);
            joiner.add(name + "@" + in + ":" + type + (required ? " (req)" : ""));
        }
        String res = joiner.toString();
        return res.isEmpty() ? "none" : res;
    }

    private String summarizeSchema(JsonNode schema) {
        if (schema == null || schema.isMissingNode()) return "none";
        String name = schema.path("name").asText(null);
        if (name != null) return name;
        if (schema.has("inlineSchema")) {
            // crude summary of inline schema keys
            JsonNode inline = schema.path("inlineSchema");
            if (inline.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = inline.fields();
                StringJoiner joiner = new StringJoiner(",");
                while (fields.hasNext()) {
                    joiner.add(fields.next().getKey());
                }
                String res = joiner.toString();
                return res.isEmpty() ? "inline" : "inline:" + res;
            }
        }
        return "inline";
    }

    private String toCommaSeparated(JsonNode array) {
        if (array == null || !array.isArray()) return null;
        StringJoiner joiner = new StringJoiner(",");
        array.forEach(n -> joiner.add(n.asText("")));
        String res = joiner.toString();
        return res.isEmpty() ? null : res;
    }

    private String safeWrite(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Failed to serialize node, using toString", e);
            return node.toString();
        }
    }
}
