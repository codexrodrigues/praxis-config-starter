package org.praxisplatform.config.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.AiRegistryTemplateBulkError;
import org.praxisplatform.config.dto.AiRegistryTemplateBulkItem;
import org.praxisplatform.config.dto.AiRegistryTemplateBulkUpsertRequest;
import org.praxisplatform.config.dto.AiRegistryTemplateBulkUpsertResponse;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateSearchResult;
import org.praxisplatform.config.dto.AiRegistryTemplateUpsertRequest;
import org.praxisplatform.config.dto.AiRegistryTemplateUpsertResponse;
import org.praxisplatform.config.service.AiRegistryTemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD de templates AI por componente.
 *
 * <p>
 * Este endpoint governa os templates persistidos no AI registry, incluindo leitura individual,
 * upsert simples, upsert em lote, exclusão e busca textual. Os payloads retornados já vêm
 * normalizados para o contrato DTO publicado pelo starter.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/ai-registry/templates")
@RequiredArgsConstructor
@Slf4j
public class AiRegistryTemplateController {

    private final AiRegistryTemplateService service;

    @GetMapping("/{componentId}")
    public ResponseEntity<AiRegistryTemplateRecord> getTemplate(
            @PathVariable String componentId) {

        Optional<AiRegistry> found = service.getTemplate(componentId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(mapToRecord(found.get()));
    }

    @PutMapping("/{componentId}")
    public ResponseEntity<AiRegistryTemplateUpsertResponse> upsert(
            @PathVariable String componentId,
            @Valid @RequestBody AiRegistryTemplateUpsertRequest request) {

        AiRegistry saved = service.upsertTemplate(
                componentId,
                request.getConfigJson(),
                request.getAiDescription(),
                request.getTemplateMeta());

        JsonNode payload = service.parsePayload(saved);
        String resolvedDescription = safeText(payload, "aiDescription");

        AiRegistryTemplateUpsertResponse response = AiRegistryTemplateUpsertResponse.builder()
                .componentId(componentId)
                .aiDescription(resolvedDescription)
                .configJson(request.getConfigJson())
                .templateMeta(request.getTemplateMeta())
                .status("upserted")
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/bulk")
    public ResponseEntity<AiRegistryTemplateBulkUpsertResponse> bulkUpsert(
            @Valid @RequestBody AiRegistryTemplateBulkUpsertRequest request) {

        List<AiRegistryTemplateBulkError> errors = new ArrayList<>();
        int accepted = 0;

        for (AiRegistryTemplateBulkItem item : request.getItems()) {
            try {
                service.upsertTemplate(
                        item.getComponentId(),
                        item.getConfigJson(),
                        item.getAiDescription(),
                        item.getTemplateMeta());
                accepted++;
            } catch (Exception e) {
                log.warn("Bulk upsert failed for componentId {}", item.getComponentId(), e);
                errors.add(AiRegistryTemplateBulkError.builder()
                        .componentId(item.getComponentId())
                        .reason(e.getMessage())
                        .build());
            }
        }

        AiRegistryTemplateBulkUpsertResponse response = AiRegistryTemplateBulkUpsertResponse.builder()
                .accepted(accepted)
                .failed(errors.size())
                .errors(errors.isEmpty() ? null : errors)
                .build();

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{componentId}")
    public ResponseEntity<Void> delete(@PathVariable String componentId) {
        Optional<AiRegistry> found = service.getTemplate(componentId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        service.deleteTemplate(found.get());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public ResponseEntity<List<AiRegistryTemplateSearchResult>> search(
            @RequestParam String query,
            @RequestParam(required = false) String componentId,
            @RequestParam(defaultValue = "5") int limit) {

        List<AiRegistryTemplateSearchResult> results = service.searchTemplates(
                query,
                componentId,
                limit);
        return ResponseEntity.ok(results);
    }

    private AiRegistryTemplateRecord mapToRecord(AiRegistry config) {
        JsonNode payload = service.parsePayload(config);
        JsonNode configJson = payload != null ? payload.get("configJson") : null;
        JsonNode templateMeta = payload != null ? payload.get("templateMeta") : null;
        return AiRegistryTemplateRecord.builder()
                .componentId(config.getRegistryKey())
                .aiDescription(safeText(payload, "aiDescription"))
                .configJson(configJson)
                .templateMeta(templateMeta)
                .build();
    }

    private String safeText(JsonNode payload, String field) {
        if (payload == null) return null;
        JsonNode value = payload.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }
}
