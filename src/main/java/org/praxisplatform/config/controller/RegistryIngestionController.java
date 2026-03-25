package org.praxisplatform.config.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de ingestão do registry de definições de componentes.
 *
 * <p>
 * Recebe um snapshot estruturado de componentes, capacidades e schemas auxiliares e o entrega ao
 * {@link RegistryIngestionService}. O retorno {@code 202 Accepted} sinaliza que a persistência foi
 * aceita pelo backend, sem prometer processamento síncrono de todo o lote.
 * </p>
 */
@RestController
@RequestMapping("/api/praxis/config/ai-registry")
@RequiredArgsConstructor
public class RegistryIngestionController {

    private final RegistryIngestionService registryIngestionService;

    @PostMapping("/component-definitions")
    public ResponseEntity<Void> ingestRegistry(
            @RequestBody @Valid RegistryIngestionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Env", required = false) String environment) {
        registryIngestionService.ingestRegistry(request, tenantId, environment);
        return ResponseEntity.accepted().build();
    }
}
