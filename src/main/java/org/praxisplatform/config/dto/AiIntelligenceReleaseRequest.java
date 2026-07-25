package org.praxisplatform.config.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AiIntelligenceReleaseRequest(
        @NotBlank String releaseId,
        @Min(0) int expectedComponentCount,
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String expectedComponentHash,
        @Min(0) int expectedTemplateCount,
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String expectedTemplateHash,
        @Min(0) long expectedChunkCount,
        @NotBlank String embeddingProfile,
        String producerRef) {}

