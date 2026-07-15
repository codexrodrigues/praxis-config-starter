package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed outcome of loading the canonical capability snapshot for a Praxis resource.
 */
public final class ResourceCapabilitiesFetchResult {

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
    private final JsonNode capabilities;
    private final Integer httpStatus;
    private final String endpointUrl;
    private final String code;
    private final String detail;

    private ResourceCapabilitiesFetchResult(
            Status status,
            JsonNode capabilities,
            Integer httpStatus,
            String endpointUrl,
            String code,
            String detail) {
        this.status = status;
        this.capabilities = capabilities;
        this.httpStatus = httpStatus;
        this.endpointUrl = endpointUrl;
        this.code = code;
        this.detail = detail;
    }

    public static ResourceCapabilitiesFetchResult success(JsonNode capabilities, String endpointUrl) {
        return new ResourceCapabilitiesFetchResult(
                Status.SUCCESS,
                capabilities,
                200,
                endpointUrl,
                "RESOURCE_CAPABILITIES_FETCH_SUCCESS",
                null);
    }

    public static ResourceCapabilitiesFetchResult failure(
            Status status,
            Integer httpStatus,
            String endpointUrl,
            String code,
            String detail) {
        return new ResourceCapabilitiesFetchResult(
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

    public JsonNode getCapabilities() {
        return capabilities;
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
        return status == Status.SUCCESS && capabilities != null;
    }

    public boolean isRetryable() {
        return status == Status.UNAVAILABLE || status == Status.TRANSPORT_ERROR;
    }
}
