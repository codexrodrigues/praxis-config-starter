package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resultado normalizado de uma tentativa de obter schema canônico para o contexto AI.
 *
 * <p>Encapsula payload, endpoint efetivo, codigo tecnico e classificacao de falha para que os
 * consumidores decidam entre seguir sem schema, reportar erro ou tentar novamente.
 */
public final class SchemaFetchResult {

    /**
     * Estados observaveis no fetch de schema remoto.
     */
    public enum Status {
        SUCCESS,
        INVALID_CONTEXT,
        BASE_URL_NOT_CONFIGURED,
        NOT_FOUND,
        UNAUTHORIZED,
        FORBIDDEN,
        BAD_REQUEST,
        CLIENT_ERROR,
        UNAVAILABLE,
        INVALID_RESPONSE,
        TRANSPORT_ERROR
    }

    private final Status status;
    private final JsonNode schema;
    private final Integer httpStatus;
    private final String endpointUrl;
    private final String code;
    private final String detail;

    private SchemaFetchResult(
            Status status,
            JsonNode schema,
            Integer httpStatus,
            String endpointUrl,
            String code,
            String detail) {
        this.status = status;
        this.schema = schema;
        this.httpStatus = httpStatus;
        this.endpointUrl = endpointUrl;
        this.code = code;
        this.detail = detail;
    }

    public static SchemaFetchResult success(JsonNode schema, String endpointUrl) {
        return new SchemaFetchResult(Status.SUCCESS, schema, 200, endpointUrl, "SCHEMA_FETCH_SUCCESS", null);
    }

    public static SchemaFetchResult failure(
            Status status,
            Integer httpStatus,
            String endpointUrl,
            String code,
            String detail) {
        return new SchemaFetchResult(status, null, httpStatus, endpointUrl, code, detail);
    }

    public Status getStatus() {
        return status;
    }

    public JsonNode getSchema() {
        return schema;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS && schema != null;
    }

    public boolean isRetryable() {
        return status == Status.UNAVAILABLE || status == Status.TRANSPORT_ERROR;
    }
}
