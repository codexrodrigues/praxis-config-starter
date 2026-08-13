package org.praxisplatform.config.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Canonical authoring input compiled into a backend-owned reactive determination materialization.
 *
 * <p>The contract intentionally references an operation by {@code operationId} instead of carrying
 * an HTTP path, method or headers. The host runtime resolves the operation against its governed API
 * catalog when it consumes an applied decision server-side; Metadata remains an independent,
 * tenant-neutral structural source.</p>
 */
public record DomainRuleReactiveDeterminationSpec(
        @Schema(
                description = "Canonical backend operation identity resolved through governed API metadata.",
                pattern = "^[A-Za-z][A-Za-z0-9._:-]{0,254}$",
                maxLength = 255)
        String operationId,
        @Schema(description = "Reactive determinations must be safe to repeat for the same semantic input.")
        Boolean idempotent,
        @Schema(description = "Reactive execution must not persist business state.")
        Persistence persistence,
        @Schema(description = "The final business command must recalculate or validate the determination.")
        Boolean finalCommandRevalidation,
        @ArraySchema(
                minItems = 1,
                maxItems = 64,
                arraySchema = @Schema(description = "Mappings from canonical resource fields to the operation request schema."))
        List<InputBinding> inputs,
        @ArraySchema(
                minItems = 1,
                maxItems = 64,
                arraySchema = @Schema(description = "Mappings from the operation response schema to canonical resource fields."))
        List<OutputBinding> outputs
) {

    public enum Persistence {
        NONE("none");

        private final String value;

        Persistence(String value) {
            this.value = value;
        }

        @JsonCreator
        public static Persistence fromValue(String value) {
            for (Persistence candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Unsupported reactive determination persistence: " + value);
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    public record InputBinding(
            @Schema(
                    description = "Non-root RFC 6901 JSON Pointer in the canonical resource input.",
                    pattern = "^/(?:[^/~*]|~[01])+(?:/(?:[^/~*]|~[01])+)*$")
            String resourcePointer,
            @Schema(
                    description = "Non-root RFC 6901 JSON Pointer in the determination request schema.",
                    pattern = "^/(?:[^/~*]|~[01])+(?:/(?:[^/~*]|~[01])+)*$")
            String requestPointer
    ) {
    }

    public record OutputBinding(
            @Schema(
                    description = "Non-root RFC 6901 JSON Pointer in the determination response schema.",
                    pattern = "^/(?:[^/~*]|~[01])+(?:/(?:[^/~*]|~[01])+)*$")
            String responsePointer,
            @Schema(
                    description = "Non-root RFC 6901 JSON Pointer in the canonical resource projection.",
                    pattern = "^/(?:[^/~*]|~[01])+(?:/(?:[^/~*]|~[01])+)*$")
            String resourcePointer
    ) {
    }
}
