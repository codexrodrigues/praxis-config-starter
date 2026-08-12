package org.praxisplatform.config.controller;

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
import org.praxisplatform.config.service.AiIntelligenceReleaseService;
import org.praxisplatform.config.service.CanonicalJsonHashService;
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
    private final AiIntelligenceReleaseService releaseService;
    private final CanonicalJsonHashService hashService;

    @GetMapping("/{componentId}")
    public ResponseEntity<AiRegistryTemplateRecord> getTemplate(
            @PathVariable String componentId) {

        Optional<AiRegistry> found = service.getTemplate(componentId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AiRegistry config = found.get();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (config.getEtag() != null) {
            response.eTag('"' + config.getEtag().toString() + '"');
        }
        return response.body(service.toRecord(config));
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

        AiRegistryTemplateRecord record = service.toRecord(saved);

        AiRegistryTemplateUpsertResponse response = AiRegistryTemplateUpsertResponse.builder()
                .componentId(componentId)
                .aiDescription(record.getAiDescription())
                .configJson(record.getConfigJson())
                .templateMeta(record.getTemplateMeta())
                .revision(record.getRevision())
                .status("upserted")
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/bulk")
    public ResponseEntity<AiRegistryTemplateBulkUpsertResponse> bulkUpsert(
            @Valid @RequestBody AiRegistryTemplateBulkUpsertRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestHeader(value = "X-Praxis-Intelligence-Release", required = false) String releaseId) {

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
        if (releaseId != null && !releaseId.isBlank() && errors.isEmpty()) {
            releaseService.observeTemplates(
                    tenantId, environment, releaseId, accepted, hashService.sha256(request));
        }

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

}
