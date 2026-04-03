package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload de entrada do endpoint de orquestraÃ§Ã£o AI.
 *
 * <p>
 * O contrato reÃºne identidade do componente, prompt do usuÃ¡rio, contexto de estado/runtime,
 * metadados de contrato e hints auxiliares para geraÃ§Ã£o de patch. Parte dos campos Ã© enriquecida
 * ou normalizada no servidor antes da delegaÃ§Ã£o ao orquestrador.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiOrchestratorRequest {
    @NotBlank
    private String componentId;
    @NotBlank
    private String componentType;
    private String userPrompt;

    private UUID sessionId;
    private String mode;
    private UUID clientTurnId;
    private List<AiChatMessage> messages;
    private String summary;
    private AiUiContextRef uiContextRef;
    private AiCurrentStateDigest currentStateDigest;

    private JsonNode currentState;
    private JsonNode dataProfile;
    private JsonNode schemaFields;
    private JsonNode runtimeState;
    private JsonNode suggestedPatch;
    private JsonNode contextHints;
    private String aiMode;
    private Boolean requireSchema;
    private String resourcePath;
    private String contractVersion;
    private String schemaHash;
    private AiSchemaContext schemaContext;
    private String variantId;

    private String apiMethod;
    private String apiTags;
    private Integer apiSearchLimit;
    private String ragReleaseId;

    // Internal stream execution flags set server-side.
    private Boolean streamTransport;
    private Boolean streamTurnPreclaimed;
}

