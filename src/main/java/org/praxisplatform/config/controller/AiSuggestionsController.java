package org.praxisplatform.config.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiSuggestionsRequest;
import org.praxisplatform.config.dto.AiSuggestionsResponse;
import org.praxisplatform.config.service.AiContextService;
import org.praxisplatform.config.service.AiSuggestionsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de sugestões AI orientadas por contexto de componente.
 *
 * <p>
 * A rota combina o request do cliente com um {@link AiContextDTO} resolvido pelo
 * {@link AiContextService}. Quando o componente não é conhecido pelo backend, responde com
 * {@code 404} e uma lista de warnings em vez de falhar silenciosamente.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/ai")
@RequiredArgsConstructor
public class AiSuggestionsController {

    private final AiSuggestionsService suggestionsService;
    private final AiContextService contextService;

    @PostMapping("/suggestions")
    public ResponseEntity<AiSuggestionsResponse> getSuggestions(
            @Valid @RequestBody AiSuggestionsRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        AiContextDTO context = contextService.buildContext(
                request.getComponentId(),
                request.getComponentType(),
                null,
                false,
                request.getCurrentState(),
                null,
                null);

        if (context.getComponentDefinition() == null) {
            AiSuggestionsResponse response = AiSuggestionsResponse.builder()
                    .suggestions(List.of())
                    .warnings(List.of("unknown_component"))
                    .build();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        return ResponseEntity.ok(suggestionsService.suggest(request, context, tenantId, environment));
    }
}
