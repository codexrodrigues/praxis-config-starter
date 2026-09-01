package org.praxisplatform.config.projection;

/** Common read-only evidence shape shared by persisted metadata and lightweight projections. */
public interface ApiMetadataCandidateEvidence {

    String getPath();

    String getMethod();

    String getTags();

    String getSummary();

    String getDescription();

    String getOperationId();

    String getRequestSchema();

    String getResponseSchema();

    String getParameters();

    String getRawJson();
}
