package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.dto.ApiCatalogRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiMetadataIngestionService {

    private final ApiMetadataRepository repository;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final RagVectorStoreService ragVectorStoreService;
    private static final Logger ingestLog = LoggerFactory.getLogger("api-metadata-ingest");

    @Transactional
    public void ingestCatalog(ApiCatalogRequest request, String tenantId, String environment) {
        if (request.getEndpoints() == null || request.getEndpoints().isEmpty()) {
            log.warn("No endpoints found in catalog request");
            return;
        }

        String resolvedTenant = normalize(tenantId);
        String resolvedEnv = normalize(environment);
        String releaseId = RagDocumentIdentity.resolveReleaseId(
                request.getReleaseId(),
                request.getVersion(),
                request.getGeneratedAt());
        String requestVersion = normalize(request.getVersion());
        for (ApiCatalogRequest.ApiEndpointEntry ep : request.getEndpoints()) {
            try {
                String path = ep.getPath();
                String method = ep.getMethod();
                
                String tags = toCommaSeparated(ep.getTags());
                String summary = ep.getSummary();
                String description = ep.getDescription();
                String operationId = ep.getOperationId();

                String requestSchema = safeWrite(ep.getRequestSchema());
                String responseSchema = safeWrite(ep.getResponseSchema());
                String parameters = safeWrite(ep.getParameters());
                String rawJson = safeWrite(ep);

                String embeddingSummary = buildSummary(path, method, tags, summary, description, operationId, ep);
                ingestLog.info(
                        "Ingest start: method={} path={} tags={} summaryLen={} descLen={} reqSchemaLen={} resSchemaLen={} paramsLen={}",
                        method,
                        path,
                        safeLen(tags),
                        safeLen(summary),
                        safeLen(description),
                        safeLen(requestSchema),
                        safeLen(responseSchema),
                        safeLen(parameters));
                ingestLog.info("Embedding input size={} sample='{}'",
                        safeLen(embeddingSummary),
                        safeSnippet(embeddingSummary));
                List<Float> embedding = embeddingService.embed(embeddingSummary);
                if (embedding == null || embedding.isEmpty()) {
                    ingestLog.warn("Embedding empty for {} {}", method, path);
                } else {
                    ingestLog.info("Embedding size={} for {} {}", embedding.size(), method, path);
                }

                ApiMetadata meta = upsert(path, method, tags, summary, description, operationId,
                        requestSchema, responseSchema, parameters, rawJson, embedding);

                log.info("Ingested api metadata: {} {}", meta.getMethod(), meta.getPath());
                ingestLog.info("Ingest saved: id={} method={} path={} embeddingSize={}",
                        meta.getId(),
                        meta.getMethod(),
                        meta.getPath(),
                        embedding != null ? embedding.size() : 0);

                Document ragDocument = toRagDocument(
                        meta,
                        embeddingSummary,
                        tags,
                        requestSchema,
                        responseSchema,
                        parameters,
                        rawJson,
                        resolvedTenant,
                        resolvedEnv,
                        releaseId,
                        requestVersion);
                ragVectorStoreService.upsertDocuments(List.of(ragDocument));
            } catch (Exception e) {
                String msg = "Error ingesting endpoint: " + ep.getMethod() + " " + ep.getPath();
                log.error(msg, e);
                ingestLog.error(msg, e);
                throw new ConfigurationIngestionException(msg, e);
            }
        }
    }

    // Helper to keep using existing logic for now, adapted for DTO
    private String buildSummary(String path, String method, String tags, String summary, String description,
                                String operationId, ApiCatalogRequest.ApiEndpointEntry ep) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add(method + " " + path);
        if (summary != null) joiner.add("Summary: " + summary);
        if (description != null) joiner.add("Desc: " + description);
        if (operationId != null) joiner.add("OpId: " + operationId);
        if (tags != null && !tags.isBlank()) joiner.add("Tags: " + tags);
        
        joiner.add("Params: " + summarizeParams(ep.getParameters()));
        
        // For complex schemas, we still rely on JsonNode navigation inside the DTO
        joiner.add("Req: " + summarizeSchema(ep.getRequestSchema()));
        joiner.add("Res: " + summarizeSchema(ep.getResponseSchema()));
        
        if (ep.getRequestSchema() != null) {
             joiner.add("ReqFields: " + summarizeFields(ep.getRequestSchema().path("fields")));
             joiner.add("ReqRelations: " + summarizeRelations(ep.getRequestSchema().path("relations")));
        }
        if (ep.getResponseSchema() != null) {
             joiner.add("ResFields: " + summarizeFields(ep.getResponseSchema().path("fields")));
             joiner.add("ResRelations: " + summarizeRelations(ep.getResponseSchema().path("relations")));
        }
        
        return joiner.toString();
    }
    
    private String toCommaSeparated(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return String.join(",", tags);
    }

    private int safeLen(String value) {
        return value == null ? 0 : value.length();
    }

    private String safeSnippet(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int limit = Math.min(160, value.length());
        return value.substring(0, limit).replaceAll("\\s+", " ").trim();
    }
    
    private String safeWrite(Object node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    // Keep existing private methods that deal with JsonNodes (summarizeFields, etc) as they are utilities
    // but remove the old ingestCatalog and old buildSummary


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

    private Document toRagDocument(
            ApiMetadata meta,
            String content,
            String tags,
            String requestSchema,
            String responseSchema,
            String parameters,
            String rawJson,
            String tenantId,
            String environment,
            String releaseId,
            String requestVersion) {
        String componentId = buildApiComponentId(meta.getMethod(), meta.getPath());
        String contentHash = RagDocumentIdentity.sha256(buildApiHashPayload(meta, rawJson));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.API_METADATA);
        metadata.put(RagMetadataKeys.RESOURCE_ID, meta.getMethod() + " " + meta.getPath());
        metadata.put(RagMetadataKeys.COMPONENT_ID, componentId);
        metadata.put(RagMetadataKeys.DOC_TYPE, RagResourceTypes.API_METADATA);
        metadata.put(RagMetadataKeys.RELEASE_ID, releaseId);
        metadata.put(RagMetadataKeys.CONTENT_HASH, contentHash);
        metadata.put(RagMetadataKeys.CHUNK_INDEX, 0);
        metadata.put(RagMetadataKeys.DB_ID, meta.getId());
        metadata.put(RagMetadataKeys.PATH, meta.getPath());
        metadata.put(RagMetadataKeys.METHOD, meta.getMethod());
        metadata.put(RagMetadataKeys.TAGS, tags);
        metadata.put(RagMetadataKeys.SUMMARY, meta.getSummary());
        metadata.put(RagMetadataKeys.DESCRIPTION, meta.getDescription());
        metadata.put(RagMetadataKeys.OPERATION_ID, meta.getOperationId());
        metadata.put(RagMetadataKeys.REQUEST_SCHEMA, requestSchema);
        metadata.put(RagMetadataKeys.RESPONSE_SCHEMA, responseSchema);
        metadata.put(RagMetadataKeys.PARAMETERS, parameters);
        if (tenantId != null) {
            metadata.put(RagMetadataKeys.TENANT_ID, tenantId);
        }
        if (environment != null) {
            metadata.put(RagMetadataKeys.ENVIRONMENT, environment);
        }
        metadata.put(RagMetadataKeys.VERSION, requestVersion != null ? requestVersion : "1");
        metadata.entrySet().removeIf(entry -> Objects.isNull(entry.getValue()));
        return Document.builder()
                .id(RagDocumentIdentity.buildDocumentId(
                        tenantId,
                        environment,
                        componentId,
                        releaseId,
                        RagResourceTypes.API_METADATA,
                        contentHash,
                        0))
                .text(content)
                .metadata(metadata)
                .build();
    }

    private String buildApiComponentId(String method, String path) {
        String normalizedMethod = normalize(method);
        String normalizedPath = normalize(path);
        String methodPart = normalizedMethod != null ? normalizedMethod.toUpperCase() : "UNKNOWN";
        String pathPart = normalizedPath != null ? normalizedPath : "unknown";
        return methodPart + ":" + pathPart;
    }

    private String buildApiHashPayload(ApiMetadata metadata, String rawJson) {
        String normalizedMethod = normalize(metadata.getMethod());
        String normalizedPath = normalize(metadata.getPath());
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(normalizedMethod != null ? normalizedMethod : "");
        joiner.add(normalizedPath != null ? normalizedPath : "");
        joiner.add(rawJson != null ? rawJson : "");
        return joiner.toString();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
