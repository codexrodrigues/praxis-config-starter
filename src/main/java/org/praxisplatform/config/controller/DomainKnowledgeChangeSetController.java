package org.praxisplatform.config.controller;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetResponse;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationResponse;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Governed Domain Knowledge change sets proposed by humans, systems or LLMs.
 */
@RestController("configDomainKnowledgeChangeSetController")
@RequestMapping("/api/praxis/config/domain-knowledge/change-sets")
@RequiredArgsConstructor
@ConditionalOnBean(DomainKnowledgeChangeSetService.class)
public class DomainKnowledgeChangeSetController {

    private final DomainKnowledgeChangeSetService changeSetService;

    @PostMapping
    public ResponseEntity<DomainKnowledgeChangeSetResponse> create(
            @RequestBody DomainKnowledgeChangeSetCreateRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.accepted().body(changeSetService.create(request, tenantId, environment));
    }

    @GetMapping
    public ResponseEntity<List<DomainKnowledgeChangeSetResponse>> list(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(changeSetService.list(tenantId, environment, status));
    }

    @GetMapping("/{changeSetId}")
    public ResponseEntity<DomainKnowledgeChangeSetResponse> get(
            @PathVariable UUID changeSetId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(changeSetService.get(changeSetId, tenantId, environment));
    }

    @PostMapping("/{changeSetId}/validate")
    public ResponseEntity<DomainKnowledgeChangeSetValidationResponse> validate(
            @PathVariable UUID changeSetId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        return ResponseEntity.ok(changeSetService.validate(changeSetId, tenantId, environment));
    }
}
