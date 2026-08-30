package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

/** Typed outcome of loading the canonical action catalog for a Praxis resource. */
public final class ResourceActionCatalogFetchResult {

    public enum Status {
        SUCCESS,
        INVALID_RESOURCE,
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
    private final JsonNode catalog;
    private final Integer httpStatus;
    private final String endpointUrl;
    private final String code;
    private final String detail;

    private ResourceActionCatalogFetchResult(
            Status status,
            JsonNode catalog,
            Integer httpStatus,
            String endpointUrl,
            String code,
            String detail) {
        this.status = status;
        this.catalog = catalog;
        this.httpStatus = httpStatus;
        this.endpointUrl = endpointUrl;
        this.code = code;
        this.detail = detail;
    }

    public static ResourceActionCatalogFetchResult success(JsonNode catalog, String endpointUrl) {
        return new ResourceActionCatalogFetchResult(
                Status.SUCCESS,
                catalog,
                200,
                endpointUrl,
                "RESOURCE_ACTION_CATALOG_FETCH_SUCCESS",
                null);
    }

    public static ResourceActionCatalogFetchResult failure(
            Status status,
            Integer httpStatus,
            String endpointUrl,
            String code,
            String detail) {
        return new ResourceActionCatalogFetchResult(
                status,
                null,
                httpStatus,
                endpointUrl,
                code,
                detail);
    }

    public Status getStatus() {
        return status;
    }

    public JsonNode getCatalog() {
        return catalog;
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
        return status == Status.SUCCESS && catalog != null;
    }

    public boolean isRetryable() {
        return status == Status.UNAVAILABLE || status == Status.TRANSPORT_ERROR;
    }
}
