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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/praxis/config/ai")
@RequiredArgsConstructor
public class AiSuggestionsController {

    private final AiSuggestionsService suggestionsService;
    private final AiContextService contextService;

    @PostMapping("/suggestions")
    public ResponseEntity<AiSuggestionsResponse> getSuggestions(
            @Valid @RequestBody AiSuggestionsRequest request) {
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

        return ResponseEntity.ok(suggestionsService.suggest(request, context));
    }
}
