package org.praxisplatform.config.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;

/** One complete host choice plus compare-and-set evidence; neither value is an authorization grant. */
@JsonDeserialize(using = EnterpriseRuntimeContextSwitchCommand.StrictDeserializer.class)
@Schema(description = "Select one host-issued complete choice with the selection version the caller observed. "
        + "Unknown, duplicate, non-string or missing properties are rejected. Tenant/profile/module dimensions "
        + "and presentation preferences are not accepted as an alternate selection command.")
public record EnterpriseRuntimeContextSwitchCommand(
        @Schema(description = "Opaque reference returned by context choices. The host resolves and reauthorizes it "
                + "for the current authenticated session; knowing a reference grants no access.",
                minLength = 1, maxLength = 4096, requiredMode = Schema.RequiredMode.REQUIRED)
        String choiceRef,
        @Schema(description = "Opaque selectionVersion last observed by this session. A stale value conflicts; "
                + "do not substitute contextVersion or a layout ETag and do not retry blindly.",
                minLength = 1, maxLength = 256, requiredMode = Schema.RequiredMode.REQUIRED)
        String expectedSelectionVersion) {
    public EnterpriseRuntimeContextSwitchCommand {
        if (choiceRef == null || choiceRef.isBlank() || choiceRef.length() > 4096
                || expectedSelectionVersion == null || expectedSelectionVersion.isBlank()
                || expectedSelectionVersion.length() > 256) {
            throw new IllegalArgumentException("INVALID_CONTEXT_COMMAND");
        }
    }

    @Override public String toString() { return "EnterpriseRuntimeContextSwitchCommand[redacted]"; }

    /** Enforces this command independently of a consumer ObjectMapper's permissive defaults. */
    public static final class StrictDeserializer extends StdDeserializer<EnterpriseRuntimeContextSwitchCommand> {
        public StrictDeserializer() { super(EnterpriseRuntimeContextSwitchCommand.class); }

        @Override
        public EnterpriseRuntimeContextSwitchCommand deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (!parser.isExpectedStartObjectToken()) throw invalid(parser);
            String choice = null;
            String version = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw invalid(parser);
                String field = parser.currentName();
                if (parser.nextToken() != JsonToken.VALUE_STRING) throw invalid(parser);
                if ("choiceRef".equals(field) && choice == null) choice = parser.getText();
                else if ("expectedSelectionVersion".equals(field) && version == null) version = parser.getText();
                else throw invalid(parser);
            }
            if (parser.getParsingContext().inRoot() && parser.nextToken() != null) throw invalid(parser);
            try {
                return new EnterpriseRuntimeContextSwitchCommand(choice, version);
            } catch (IllegalArgumentException rejected) {
                throw invalid(parser);
            }
        }

        private static JsonMappingException invalid(JsonParser parser) {
            return JsonMappingException.from(parser, "INVALID_CONTEXT_COMMAND");
        }
    }
}
