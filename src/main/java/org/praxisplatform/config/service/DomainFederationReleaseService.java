package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.dto.DomainFederationReleaseResponse;
import org.praxisplatform.config.dto.DomainFederationReleaseValidationResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DomainFederationReleaseService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final DomainFederationReleaseRepository releaseRepository;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<DomainFederationReleaseResponse> releases(
            String tenantId,
            String environment,
            String status,
            int limit) {
        String resolvedTenant = normalize(tenantId);
        String resolvedEnvironment = normalize(environment);
        String resolvedStatus = normalize(status);
        int effectiveLimit = clampLimit(limit);
        return releaseRepository.findByFilters(resolvedTenant, resolvedEnvironment, resolvedStatus).stream()
                .limit(effectiveLimit)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainFederationReleaseValidationResponse validation(
            String releaseKey,
            String tenantId,
            String environment) {
        if (!StringUtils.hasText(releaseKey)) {
            throw new ConfigurationIngestionException("releaseKey is required");
        }
        String resolvedReleaseKey = releaseKey.trim();
        String resolvedTenant = normalize(tenantId);
        String resolvedEnvironment = normalize(environment);
        DomainFederationRelease release = releaseRepository
                .findByReleaseKeyAndOptionalScope(resolvedReleaseKey, resolvedTenant, resolvedEnvironment)
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Domain federation release not found: " + resolvedReleaseKey));
        return new DomainFederationReleaseValidationResponse(
                release.getId(),
                release.getReleaseKey(),
                release.getTenantId(),
                release.getEnvironment(),
                release.getStatus(),
                release.getPayloadHash(),
                read(release.getValidationReport()),
                release.getCreatedAt());
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainFederationReleaseResponse activate(
            String releaseKey,
            String tenantId,
            String environment) {
        if (!StringUtils.hasText(releaseKey)) {
            throw new ConfigurationIngestionException("releaseKey is required");
        }
        String resolvedReleaseKey = releaseKey.trim();
        String resolvedTenant = normalize(tenantId);
        String resolvedEnvironment = normalize(environment);
        DomainFederationRelease release = releaseRepository
                .findByReleaseKeyAndOptionalScope(resolvedReleaseKey, resolvedTenant, resolvedEnvironment)
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Domain federation release not found: " + resolvedReleaseKey));
        if (!"candidate".equals(release.getStatus())) {
            throw new ConfigurationIngestionException(
                    "Only candidate federation releases can be activated: " + resolvedReleaseKey);
        }

        Instant now = Instant.now();
        releaseRepository.findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
                        release.getTenantId(),
                        release.getEnvironment(),
                        "active")
                .stream()
                .filter(activeRelease -> !activeRelease.getId().equals(release.getId()))
                .forEach(activeRelease -> {
                    activeRelease.setStatus("superseded");
                    releaseRepository.save(activeRelease);
                });

        release.setStatus("active");
        release.setActivatedAt(now);
        return toResponse(releaseRepository.save(release));
    }

    private DomainFederationReleaseResponse toResponse(DomainFederationRelease release) {
        return new DomainFederationReleaseResponse(
                release.getId(),
                release.getReleaseKey(),
                release.getTenantId(),
                release.getEnvironment(),
                release.getStatus(),
                release.getPayloadHash(),
                release.getCreatedBy(),
                release.getCreatedAt(),
                release.getActivatedAt());
    }

    private JsonNode read(String raw) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to read domain federation validation report", ex);
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
