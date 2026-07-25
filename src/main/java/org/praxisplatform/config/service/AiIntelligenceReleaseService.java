package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.AiIntelligenceRelease;
import org.praxisplatform.config.dto.*;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiIntelligenceReleaseRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiIntelligenceReleaseService {
    private final AiIntelligenceReleaseRepository repository;
    private final RagVectorStoreService ragVectorStoreService;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public AiIntelligenceRelease stage(String tenant, String environment, AiIntelligenceReleaseRequest request) {
        String t = normalize(tenant, "global");
        String e = normalize(environment, "global");
        repository.findByTenantIdAndEnvironmentAndReleaseId(t, e, request.releaseId()).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Intelligence release already exists.");
        });
        return repository.save(AiIntelligenceRelease.builder()
                .releaseId(request.releaseId().trim()).tenantId(t).environment(e).status("STAGING")
                .expectedComponentCount(request.expectedComponentCount())
                .expectedComponentHash(request.expectedComponentHash().toLowerCase())
                .expectedTemplateCount(request.expectedTemplateCount())
                .expectedTemplateHash(request.expectedTemplateHash().toLowerCase())
                .expectedChunkCount(request.expectedChunkCount())
                .embeddingProfile(request.embeddingProfile().trim()).producerRef(trim(request.producerRef()))
                .build());
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public void observeComponents(String tenant, String environment, String releaseId,
            int componentCount, String componentHash, long chunkCount, String componentCorpusReleaseId) {
        AiIntelligenceRelease release = require(tenant, environment, releaseId);
        requireStatus(release, "STAGING");
        release.setObservedComponentCount(componentCount);
        release.setObservedComponentHash(componentHash);
        release.setObservedChunkCount(chunkCount);
        release.setComponentCorpusReleaseId(normalizeRequired(
                componentCorpusReleaseId, "Component corpus release id is required."));
        repository.save(release);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public void observeTemplates(String tenant, String environment, String releaseId,
            int templateCount, String templateHash) {
        AiIntelligenceRelease release = require(tenant, environment, releaseId);
        requireStatus(release, "STAGING");
        release.setObservedTemplateCount(templateCount);
        release.setObservedTemplateHash(templateHash);
        repository.save(release);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public AiIntelligenceRelease activate(String tenant, String environment, String releaseId) {
        AiIntelligenceRelease release = require(tenant, environment, releaseId);
        requireStatus(release, "STAGING");
        List<String> mismatches = mismatches(release);
        if (!mismatches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Intelligence release is not reconciled: " + String.join(", ", mismatches));
        }
        repository.findByTenantIdAndEnvironmentAndStatus(release.getTenantId(), release.getEnvironment(), "ACTIVE")
                .ifPresent(active -> {
                    active.setStatus("SUPERSEDED");
                    repository.saveAndFlush(active);
                });
        release.setStatus("ACTIVE");
        release.setActivatedAt(Instant.now());
        return repository.save(release);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public AiIntelligenceRelease fail(String tenant, String environment, String releaseId, String reason) {
        AiIntelligenceRelease release = require(tenant, environment, releaseId);
        requireStatus(release, "STAGING");
        release.setStatus("FAILED"); release.setFailureReason(trim(reason));
        return repository.save(release);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public AiIntelligenceRelease get(String tenant, String environment, String releaseId) {
        return require(tenant, environment, releaseId);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public RagVectorStoreService.SupersededReleaseCleanupPlan cleanupPlan(
            String tenant, String environment, String releaseId) {
        AiIntelligenceRelease release = require(tenant, environment, releaseId);
        requireStatus(release, "ACTIVE");
        String componentCorpusReleaseId = normalizeRequired(
                release.getComponentCorpusReleaseId(),
                "Active intelligence release has no observed component corpus release id.");
        return ragVectorStoreService.planDocumentsByResourceTypeExceptRelease(
                release.getTenantId(),
                release.getEnvironment(),
                componentCorpusReleaseId,
                RagResourceTypes.COMPONENT_DEFINITION);
    }

    private List<String> mismatches(AiIntelligenceRelease r) {
        var failures = new java.util.ArrayList<String>();
        if (!java.util.Objects.equals(r.getObservedComponentCount(), r.getExpectedComponentCount())) failures.add("component-count");
        if (!java.util.Objects.equals(r.getObservedComponentHash(), r.getExpectedComponentHash())) failures.add("component-hash");
        if (!java.util.Objects.equals(r.getObservedTemplateCount(), r.getExpectedTemplateCount())) failures.add("template-count");
        if (!java.util.Objects.equals(r.getObservedTemplateHash(), r.getExpectedTemplateHash())) failures.add("template-hash");
        if (!java.util.Objects.equals(r.getObservedChunkCount(), r.getExpectedChunkCount())) failures.add("chunk-count");
        return failures;
    }
    private AiIntelligenceRelease require(String tenant, String env, String id) {
        return repository.findByTenantIdAndEnvironmentAndReleaseId(normalize(tenant,"global"), normalize(env,"global"), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Intelligence release not found."));
    }
    private void requireStatus(AiIntelligenceRelease r, String status) {
        if (!status.equals(r.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Release is not " + status + ".");
    }
    private String normalize(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
        return value.trim();
    }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
