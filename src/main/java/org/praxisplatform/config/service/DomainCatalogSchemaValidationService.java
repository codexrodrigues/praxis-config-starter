package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DomainCatalogSchemaValidationService {

    private static final String V1_SCHEMA_VERSION = "praxis.domain-catalog/v0.1";
    private static final String V2_SCHEMA_VERSION = "praxis.domain-catalog/v0.2";
    private static final Map<String, String> SCHEMA_RESOURCES = Map.of(
            V1_SCHEMA_VERSION,
            "/domain-catalog/contracts/praxis-domain-catalog-v0.1.schema.json",
            V2_SCHEMA_VERSION,
            "/domain-catalog/contracts/praxis-domain-catalog-v0.2.schema.json"
    );

    private final ObjectMapper objectMapper;
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public void validate(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new ConfigurationIngestionException("Domain catalog payload must be a JSON object");
        }
        String schemaVersion = payload.path("schemaVersion").asText(null);
        if (!StringUtils.hasText(schemaVersion)) {
            throw new ConfigurationIngestionException("Domain catalog field 'schemaVersion' is required");
        }
        JsonSchema schema = schemaCache.computeIfAbsent(schemaVersion, this::loadSchema);
        Set<ValidationMessage> errors = schema.validate(payload);
        if (!errors.isEmpty()) {
            String message = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .findFirst()
                    .orElse("payload does not match schema");
            throw new ConfigurationIngestionException(
                    "Domain catalog payload does not match " + schemaVersion + ": " + message);
        }
    }

    private JsonSchema loadSchema(String schemaVersion) {
        String resource = SCHEMA_RESOURCES.get(schemaVersion);
        if (!StringUtils.hasText(resource)) {
            throw new ConfigurationIngestionException(
                    "Unsupported domain catalog schemaVersion '" + schemaVersion + "'");
        }
        try (InputStream input = DomainCatalogSchemaValidationService.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new ConfigurationIngestionException("Domain catalog schema resource not found: " + resource);
            }
            JsonNode schemaNode = objectMapper.readTree(input);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(schemaNode);
        } catch (ConfigurationIngestionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to load domain catalog schema " + schemaVersion, ex);
        }
    }
}
