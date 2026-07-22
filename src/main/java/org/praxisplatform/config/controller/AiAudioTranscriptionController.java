package org.praxisplatform.config.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiAudioTranscriptionResponse;
import org.praxisplatform.config.service.AiAudioTranscriptionRequest;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/praxis/config/ai/transcriptions")
@RequiredArgsConstructor
public class AiAudioTranscriptionController {

    private static final long MAX_AUDIO_BYTES = 12L * 1024L * 1024L;
    private final AiProviderManagementService managementService;
    private final AiPrincipalContextResolver principalContextResolver;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AiAudioTranscriptionResponse> transcribe(
            @RequestPart("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false) String language,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Env", required = false) String environment,
            HttpServletRequest servletRequest) throws IOException {
        if (audio.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio payload is required.");
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio payload exceeds the 12 MiB limit.");
        }
        AiPrincipalContext principal = principalContextResolver.resolve(servletRequest, tenantId, userId, environment);
        try {
            return ResponseEntity.ok(managementService.transcribeAudio(
                    new AiAudioTranscriptionRequest(
                            audio.getBytes(),
                            audio.getOriginalFilename(),
                            audio.getContentType(),
                            language),
                    principal.tenantId(),
                    principal.userId(),
                    principal.environment()));
        } catch (UnsupportedOperationException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), exception);
        }
    }
}
