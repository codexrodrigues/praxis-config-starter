package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainContext;
import org.praxisplatform.config.domain.DomainContextRelationship;
import org.praxisplatform.config.domain.DomainContract;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.domain.DomainResolution;
import org.praxisplatform.config.domain.DomainSource;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextRelationship;
import org.praxisplatform.config.dto.DomainFederationContract;
import org.praxisplatform.config.dto.DomainFederationIngestCountsResponse;
import org.praxisplatform.config.dto.DomainFederationIngestResponse;
import org.praxisplatform.config.dto.DomainFederationResolution;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainContextRelationshipRepository;
import org.praxisplatform.config.repository.DomainContextRepository;
import org.praxisplatform.config.repository.DomainContractRepository;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;
import org.praxisplatform.config.repository.DomainResolutionRepository;
import org.praxisplatform.config.repository.DomainSourceRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DomainFederationIngestPersistenceService {

    private static final String SCHEMA_VERSION = "praxis.domain-federation-ingest/v0.1";

    private final DomainFederationContractValidator validator;
    private final DomainFederationReleaseRepository releaseRepository;
    private final DomainSourceRepository sourceRepository;
    private final DomainContextRepository contextRepository;
    private final DomainContextRelationshipRepository relationshipRepository;
    private final DomainContractRepository contractRepository;
    private final DomainResolutionRepository resolutionRepository;
    private final DomainCatalogReleaseRepository catalogReleaseRepository;
    private final ObjectMapper objectMapper;
    private final boolean persistenceEnabled;

    public DomainFederationIngestPersistenceService(
            DomainFederationContractValidator validator,
            DomainFederationReleaseRepository releaseRepository,
            DomainSourceRepository sourceRepository,
            DomainContextRepository contextRepository,
            DomainContextRelationshipRepository relationshipRepository,
            DomainContractRepository contractRepository,
            DomainResolutionRepository resolutionRepository,
            DomainCatalogReleaseRepository catalogReleaseRepository,
            ObjectMapper objectMapper,
            @Value("${praxis.domain-federation.persistence.enabled:false}") boolean persistenceEnabled) {
        this.validator = validator;
        this.releaseRepository = releaseRepository;
        this.sourceRepository = sourceRepository;
        this.contextRepository = contextRepository;
        this.relationshipRepository = relationshipRepository;
        this.contractRepository = contractRepository;
        this.resolutionRepository = resolutionRepository;
        this.catalogReleaseRepository = catalogReleaseRepository;
        this.objectMapper = objectMapper;
        this.persistenceEnabled = persistenceEnabled;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainFederationIngestResponse ingestCandidate(DomainFederationValidationRequest request) {
        if (!persistenceEnabled) {
            throw new ConfigurationIngestionException(
                    "Persistent federation ingest is disabled. Enable praxis.domain-federation.persistence.enabled=true.");
        }
        DomainFederationValidationReport validation = validator.validate(request);
        String payloadHash = hash(request);
        if (!validation.valid()) {
            return new DomainFederationIngestResponse(
                    SCHEMA_VERSION,
                    false,
                    false,
                    null,
                    null,
                    "rejected",
                    payloadHash,
                    new DomainFederationIngestCountsResponse(0, 0, 0, 0, 0),
                    validation);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String releaseKey = releaseKey(request, payloadHash, now);
        DomainFederationRelease release = releaseRepository.save(DomainFederationRelease.builder()
                .tenantId(normalize(request.tenantId()))
                .environment(normalize(request.environment()))
                .releaseKey(releaseKey)
                .status("candidate")
                .sourceReleaseIds("[]")
                .validationReport(write(validation))
                .payloadHash(payloadHash)
                .createdBy("system")
                .build());

        List<DomainSource> sources = sourceRepository.saveAll(sources(request.sources(), release));
        List<DomainContext> contexts = contextRepository.saveAll(contexts(request.contexts(), release));
        List<DomainContextRelationship> relationships =
                relationshipRepository.saveAll(relationships(request.contextRelationships(), release));
        List<DomainContract> contracts = contractRepository.saveAll(contracts(request.contracts(), release));
        List<DomainResolution> resolutions = resolutionRepository.saveAll(resolutions(request.resolutions(), release));

        return new DomainFederationIngestResponse(
                SCHEMA_VERSION,
                false,
                true,
                release.getId(),
                release.getReleaseKey(),
                release.getStatus(),
                payloadHash,
                new DomainFederationIngestCountsResponse(
                        sources.size(),
                        contexts.size(),
                        relationships.size(),
                        contracts.size(),
                        resolutions.size()),
                validation);
    }

    private List<DomainSource> sources(List<DomainFederationSource> sources, DomainFederationRelease release) {
        return safe(sources).stream()
                .map(source -> DomainSource.builder()
                        .federationRelease(release)
                        .tenantId(normalize(source.tenantId()))
                        .environment(normalize(source.environment()))
                        .sourceKey(trim(source.sourceKey()))
                        .sourceType(trim(source.sourceType()))
                        .serviceKey(normalize(source.serviceKey()))
                        .serviceName(normalize(source.serviceName()))
                        .semanticOwner(normalize(source.semanticOwner()))
                        .technicalOwner(normalize(source.technicalOwner()))
                        .trustLevel(trim(source.trustLevel()))
                        .status(trim(source.status()))
                        .latestRelease(resolveCatalogRelease(source.latestReleaseKey()))
                        .latestReleaseKey(normalize(source.latestReleaseKey()))
                        .evidence(writeOrDefault(source.evidence()))
                        .build())
                .toList();
    }

    private List<DomainContext> contexts(List<DomainFederationContext> contexts, DomainFederationRelease release) {
        return safe(contexts).stream()
                .map(context -> DomainContext.builder()
                        .federationRelease(release)
                        .tenantId(normalize(context.tenantId()))
                        .environment(normalize(context.environment()))
                        .contextKey(trim(context.contextKey()))
                        .sourceKey(trim(context.sourceKey()))
                        .contextType(trim(context.contextType()))
                        .label(normalize(context.label()))
                        .description(normalize(context.description()))
                        .semanticOwner(normalize(context.semanticOwner()))
                        .technicalOwner(normalize(context.technicalOwner()))
                        .status(trim(context.status()))
                        .latestReleaseKey(normalize(context.latestReleaseKey()))
                        .evidence(writeOrDefault(context.evidence()))
                        .build())
                .toList();
    }

    private List<DomainContextRelationship> relationships(
            List<DomainFederationContextRelationship> relationships,
            DomainFederationRelease release) {
        return safe(relationships).stream()
                .map(relationship -> DomainContextRelationship.builder()
                        .federationRelease(release)
                        .tenantId(release.getTenantId())
                        .environment(release.getEnvironment())
                        .relationshipKey(trim(relationship.relationshipKey()))
                        .sourceContextKey(trim(relationship.sourceContextKey()))
                        .targetContextKey(trim(relationship.targetContextKey()))
                        .relationshipType(trim(relationship.relationshipType()))
                        .contractKey(normalize(relationship.contractKey()))
                        .direction(trim(relationship.direction()))
                        .ownership(trim(relationship.ownership()))
                        .confidence(relationship.confidence())
                        .status(trim(relationship.status()))
                        .evidence(writeOrDefault(relationship.evidence()))
                        .build())
                .toList();
    }

    private List<DomainContract> contracts(List<DomainFederationContract> contracts, DomainFederationRelease release) {
        return safe(contracts).stream()
                .map(contract -> DomainContract.builder()
                        .federationRelease(release)
                        .tenantId(release.getTenantId())
                        .environment(release.getEnvironment())
                        .contractKey(trim(contract.contractKey()))
                        .contractType(trim(contract.contractType()))
                        .providerSourceKey(trim(contract.providerSourceKey()))
                        .providerContextKey(trim(contract.providerContextKey()))
                        .consumerContextKey(normalize(contract.consumerContextKey()))
                        .resourceKey(normalize(contract.resourceKey()))
                        .operationKey(normalize(contract.operationKey()))
                        .schemaRef(normalize(contract.schemaRef()))
                        .compatibility(trim(contract.compatibility()))
                        .visibility(trim(contract.visibility()))
                        .status(trim(contract.status()))
                        .evidence(writeOrDefault(contract.evidence()))
                        .build())
                .toList();
    }

    private List<DomainResolution> resolutions(List<DomainFederationResolution> resolutions, DomainFederationRelease release) {
        return safe(resolutions).stream()
                .map(resolution -> DomainResolution.builder()
                        .federationRelease(release)
                        .tenantId(release.getTenantId())
                        .environment(release.getEnvironment())
                        .resolutionKey(trim(resolution.resolutionKey()))
                        .sourceConceptKey(trim(resolution.sourceConceptKey()))
                        .targetConceptKey(trim(resolution.targetConceptKey()))
                        .sourceContextKey(trim(resolution.sourceContextKey()))
                        .targetContextKey(trim(resolution.targetContextKey()))
                        .resolutionType(trim(resolution.resolutionType()))
                        .confidence(resolution.confidence())
                        .status(trim(resolution.status()))
                        .reviewOwner(normalize(resolution.reviewOwner()))
                        .evidence(writeOrDefault(resolution.evidence()))
                        .build())
                .toList();
    }

    private DomainCatalogRelease resolveCatalogRelease(String releaseKey) {
        if (!StringUtils.hasText(releaseKey)) {
            return null;
        }
        return catalogReleaseRepository.findByReleaseKey(releaseKey.trim()).orElse(null);
    }

    private String releaseKey(DomainFederationValidationRequest request, String payloadHash, Instant createdAt) {
        String tenant = normalizeOrDefault(request.tenantId(), "global");
        String environment = normalizeOrDefault(request.environment(), "default");
        String timestamp = createdAt.toString().replace(':', '-');
        return "domain-federation:%s:%s:%s:%s".formatted(tenant, environment, timestamp, payloadHash.substring(0, 12));
    }

    private String hash(DomainFederationValidationRequest request) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to hash domain federation request", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to serialize domain federation payload", ex);
        }
    }

    private String writeOrDefault(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        return write(node);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
