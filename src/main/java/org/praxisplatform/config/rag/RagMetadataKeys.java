package org.praxisplatform.config.rag;

public final class RagMetadataKeys {
    public static final String RESOURCE_TYPE = "resourceType";
    public static final String RESOURCE_ID = "resourceId";
    public static final String VERSION = "version";
    public static final String TENANT_ID = "tenantId";
    public static final String ENVIRONMENT = "environment";
    public static final String RELEASE_ID = "releaseId";
    public static final String COMPONENT_ID = "componentId";
    public static final String DOC_TYPE = "docType";
    public static final String CONTENT_HASH = "contentHash";
    public static final String CHUNK_INDEX = "chunkIndex";

    public static final String DB_ID = "dbId";
    public static final String PATH = "path";
    public static final String METHOD = "method";
    public static final String TAGS = "tags";
    public static final String SUMMARY = "summary";
    public static final String DESCRIPTION = "description";
    public static final String OPERATION_ID = "operationId";
    public static final String REQUEST_SCHEMA = "requestSchema";
    public static final String RESPONSE_SCHEMA = "responseSchema";
    public static final String PARAMETERS = "parameters";
    public static final String JSON_SCHEMA = "jsonSchema";
    public static final String AUTHORING_MANIFEST_VERSION = "authoringManifestVersion";
    public static final String AUTHORING_OPERATION_COUNT = "authoringOperationCount";
    public static final String AUTHORING_TARGET_COUNT = "authoringTargetCount";

    public static final String SOURCE_KIND = "sourceKind";
    public static final String SOURCE_ID = "sourceId";
    public static final String SOURCE_POINTER = "sourcePointer";
    public static final String CHUNK_KIND = "chunkKind";
    public static final String PUBLISHED_AT = "publishedAt";
    public static final String EMBEDDING_PROFILE = "embeddingProfile";
    public static final String CORPUS_VERSION = "corpusVersion";

    public static final String DOMAIN_KNOWLEDGE_CONCEPT_ID = "domainKnowledgeConceptId";
    public static final String DOMAIN_KNOWLEDGE_CONCEPT_KEY = "domainKnowledgeConceptKey";
    public static final String DOMAIN_KNOWLEDGE_EVIDENCE_ID = "domainKnowledgeEvidenceId";
    public static final String DOMAIN_KNOWLEDGE_EVIDENCE_KEY = "domainKnowledgeEvidenceKey";
    public static final String DOMAIN_KNOWLEDGE_EVIDENCE_STATUS = "domainKnowledgeEvidenceStatus";
    public static final String AI_VISIBILITY = "aiVisibility";
    public static final String CONTEXT_KEY = "contextKey";
    public static final String RESOURCE_KEY = "resourceKey";

    private RagMetadataKeys() {
    }
}
