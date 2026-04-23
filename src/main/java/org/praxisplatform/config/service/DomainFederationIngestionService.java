package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainContext;
import org.praxisplatform.config.domain.DomainContextRelationship;
import org.praxisplatform.config.domain.DomainContract;
import org.praxisplatform.config.domain.DomainFederationRelease;
import org.praxisplatform.config.domain.DomainResolution;
import org.praxisplatform.config.domain.DomainSource;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextRelationship;
import org.praxisplatform.config.dto.DomainFederationContract;
import org.praxisplatform.config.dto.DomainFederationIngestResponse;
import org.praxisplatform.config.dto.DomainFederationResolution;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationReport;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;
import org.praxisplatform.config.repository.DomainContextRelationshipRepository;
import org.praxisplatform.config.repository.DomainContextRepository;
import org.praxisplatform.config.repository.DomainContractRepository;
import org.praxisplatform.config.repository.DomainFederationReleaseRepository;
import org.praxisplatform.config.repository.DomainResolutionRepository;
import org.praxisplatform.config.repository.DomainSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DomainFederationIngestionService {

    private static final String SCHEMA_VERSION = "praxis.domain-federation-ingest/v0.1";

    private final DomainFederationContractValidator validator;
    private final DomainFederationIngestDryRunService dryRunService;
    private final DomainFederationReleaseRepository releaseRepository;
    private final DomainSourceRepository sourceRepository;
    private final DomainContextRepository contextRepository;
    private final DomainContextRelationshipRepository relationshipRepository;
    private final DomainContractRepository contractRepository;
    private final DomainResolutionRepository resolutionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public DomainFederationIngestResponse ingest(DomainFederationValidationRequest request) {
        DomainFederationValidationReport validation = validator.validate(request);
        if (!validation.valid()) {
            return new DomainFederationIngestResponse(
                    SCHEMA_VERSION,
                    false,
                    false,
                    false,
                    null,
                    null,
                    0,
                    validation,
                    List.of());
        }

        String payloadHash = sha256(write(request));
        String releaseKey = releaseKey(request, payloadHash);
        String tenantId = normalize(request.tenantId());
        String environment = normalize(request.environment());

        DomainFederationRelease release = releaseRepository
                .findByTenantIdAndEnvironmentAndReleaseKey(tenantId, environment, releaseKey)
                .orElseGet(DomainFederationRelease::new);
        release.setTenantId(tenantId);
        release.setEnvironment(environment);
        release.setReleaseKey(releaseKey);
        release.setStatus("candidate");
        release.setPayloadHash(payloadHash);
        release.setSourceReleaseIds(write(sourceReleaseKeys(request)));
        release.setValidationReport(write(validation));
        release.setCreatedBy("system");
        release = releaseRepository.save(release);

        clearReleaseRows(release);
        int itemCount = persistRows(request, release);

        var dryRun = dryRunService.dryRun(request);
        return new DomainFederationIngestResponse(
                SCHEMA_VERSION,
                false,
                true,
                true,
                release.getId(),
                release.getReleaseKey(),
                itemCount,
                validation,
                dryRun.previews());
    }

    private void clearReleaseRows(DomainFederationRelease release) {
        if (release.getId() == null) {
            return;
        }
        resolutionRepository.deleteAll(resolutionRepository.findByFederationRelease_IdOrderByResolutionKey(release.getId()));
        contractRepository.deleteAll(contractRepository.findByFederationRelease_IdOrderByContractKey(release.getId()));
        relationshipRepository.deleteAll(relationshipRepository.findByFederationRelease_IdOrderByRelationshipKey(release.getId()));
        contextRepository.deleteAll(contextRepository.findByFederationRelease_IdOrderByContextKey(release.getId()));
        sourceRepository.deleteAll(sourceRepository.findByFederationRelease_IdOrderBySourceKey(release.getId()));
    }

    private int persistRows(DomainFederationValidationRequest request, DomainFederationRelease release) {
        List<DomainSource> sources = sources(request, release);
        List<DomainContext> contexts = contexts(request, release);
        List<DomainContextRelationship> relationships = relationships(request, release);
        List<DomainContract> contracts = contracts(request, release);
        List<DomainResolution> resolutions = resolutions(request, release);
        sourceRepository.saveAll(sources);
        contextRepository.saveAll(contexts);
        relationshipRepository.saveAll(relationships);
        contractRepository.saveAll(contracts);
        resolutionRepository.saveAll(resolutions);
        return sources.size() + contexts.size() + relationships.size() + contracts.size() + resolutions.size();
    }

    private List<DomainSource> sources(DomainFederationValidationRequest request, DomainFederationRelease release) {
        List<DomainFederationSource> sources = request.sources() == null ? List.of() : request.sources();
        return sources.stream()
                .map(source -> DomainSource.builder()
                        .federationRelease(release)
                        .tenantId(firstText(source.tenantId(), request.tenantId()))
                        .environment(firstText(source.environment(), request.environment()))
                        .sourceKey(source.sourceKey())
                        .sourceType(source.sourceType())
                        .serviceKey(source.serviceKey())
                        .serviceName(source.serviceName())
                        .semanticOwner(source.semanticOwner())
                        .technicalOwner(source.technicalOwner())
                        .trustLevel(source.trustLevel())
                        .status(source.status())
                        .latestReleaseKey(source.latestReleaseKey())
                        .evidence(writeEvidence(source.evidence()))
                        .build())
                .toList();
    }

    private List<DomainContext> contexts(DomainFederationValidationRequest request, DomainFederationRelease release) {
        List<DomainFederationContext> contexts = request.contexts() == null ? List.of() : request.contexts();
        return contexts.stream()
                .map(context -> DomainContext.builder()
                        .federationRelease(release)
                        .tenantId(firstText(context.tenantId(), request.tenantId()))
                        .environment(firstText(context.environment(), request.environment()))
                        .contextKey(context.contextKey())
                        .sourceKey(context.sourceKey())
                        .contextType(context.contextType())
                        .label(context.label())
                        .description(context.description())
                        .semanticOwner(context.semanticOwner())
                        .technicalOwner(context.technicalOwner())
                        .status(context.status())
                        .latestReleaseKey(context.latestReleaseKey())
                        .evidence(writeEvidence(context.evidence()))
                        .build())
                .toList();
    }

    private List<DomainContextRelationship> relationships(
            DomainFederationValidationRequest request,
            DomainFederationRelease release) {
        List<DomainFederationContextRelationship> relationships =
                request.contextRelationships() == null ? List.of() : request.contextRelationships();
        return relationships.stream()
                .map(relationship -> DomainContextRelationship.builder()
                        .federationRelease(release)
                        .tenantId(request.tenantId())
                        .environment(request.environment())
                        .relationshipKey(relationship.relationshipKey())
                        .sourceContextKey(relationship.sourceContextKey())
                        .targetContextKey(relationship.targetContextKey())
                        .relationshipType(relationship.relationshipType())
                        .contractKey(relationship.contractKey())
                        .direction(relationship.direction())
                        .ownership(relationship.ownership())
                        .confidence(relationship.confidence())
                        .status(relationship.status())
                        .evidence(writeEvidence(relationship.evidence()))
                        .build())
                .toList();
    }

    private List<DomainContract> contracts(DomainFederationValidationRequest request, DomainFederationRelease release) {
        List<DomainFederationContract> contracts = request.contracts() == null ? List.of() : request.contracts();
        return contracts.stream()
                .map(contract -> DomainContract.builder()
                        .federationRelease(release)
                        .tenantId(request.tenantId())
                        .environment(request.environment())
                        .contractKey(contract.contractKey())
                        .contractType(contract.contractType())
                        .providerSourceKey(contract.providerSourceKey())
                        .providerContextKey(contract.providerContextKey())
                        .consumerContextKey(contract.consumerContextKey())
                        .resourceKey(contract.resourceKey())
                        .operationKey(contract.operationKey())
                        .schemaRef(contract.schemaRef())
                        .compatibility(contract.compatibility())
                        .visibility(contract.visibility())
                        .status(contract.status())
                        .evidence(writeEvidence(contract.evidence()))
                        .build())
                .toList();
    }

    private List<DomainResolution> resolutions(DomainFederationValidationRequest request, DomainFederationRelease release) {
        List<DomainFederationResolution> resolutions = request.resolutions() == null ? List.of() : request.resolutions();
        return resolutions.stream()
                .map(resolution -> DomainResolution.builder()
                        .federationRelease(release)
                        .tenantId(request.tenantId())
                        .environment(request.environment())
                        .resolutionKey(resolution.resolutionKey())
                        .sourceConceptKey(resolution.sourceConceptKey())
                        .targetConceptKey(resolution.targetConceptKey())
                        .sourceContextKey(resolution.sourceContextKey())
                        .targetContextKey(resolution.targetContextKey())
                        .resolutionType(resolution.resolutionType())
                        .confidence(resolution.confidence())
                        .status(resolution.status())
                        .reviewOwner(resolution.reviewOwner())
                        .evidence(writeEvidence(resolution.evidence()))
                        .build())
                .toList();
    }

    private List<String> sourceReleaseKeys(DomainFederationValidationRequest request) {
        List<DomainFederationSource> sources = request.sources() == null ? List.of() : request.sources();
        return sources.stream()
                .map(DomainFederationSource::latestReleaseKey)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String releaseKey(DomainFederationValidationRequest request, String payloadHash) {
        String tenant = firstText(request.tenantId(), "global");
        String environment = firstText(request.environment(), "default");
        return "domain-federation:" + tenant + ":" + environment + ":" + payloadHash.substring(0, 16);
    }

    private String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : normalize(fallback);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String writeEvidence(JsonNode value) {
        return value == null || value.isNull() ? "{}" : write(value);
    }

    private String write(Object value) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize domain federation payload", ex);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }
}
