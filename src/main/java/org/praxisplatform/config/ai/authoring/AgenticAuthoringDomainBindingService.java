package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.springframework.util.StringUtils;

/** Resolves safe operational bindings from governed Domain Knowledge rows. */
public class AgenticAuthoringDomainBindingService {

    private final DomainKnowledgeBindingRepository bindingRepository;
    private final DomainKnowledgeEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;
    private String catalogFallbackTenantId = "";
    private String catalogFallbackEnvironment = "";

    public void setCatalogFallbackScope(String tenantId, String environment) {
        this.catalogFallbackTenantId = tenantId == null ? "" : tenantId.trim();
        this.catalogFallbackEnvironment = environment == null ? "" : environment.trim();
    }

    public AgenticAuthoringDomainBindingService(
            DomainKnowledgeBindingRepository bindingRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository) {
        this(bindingRepository, evidenceRepository, new ObjectMapper());
    }

    public AgenticAuthoringDomainBindingService(
            DomainKnowledgeBindingRepository bindingRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository,
            ObjectMapper objectMapper) {
        this.bindingRepository = bindingRepository;
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    List<BindingProjection> resolve(String tenantId, String environment, String resourceKey, int limit) {
        if (!StringUtils.hasText(tenantId)
                || !StringUtils.hasText(environment)
                || !StringUtils.hasText(resourceKey)) {
            return List.of();
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), 12);
        String scopedTenant = tenantId.trim();
        String scopedEnvironment = environment.trim();
        List<BindingProjection> resolved = resolveExactScope(scopedTenant, scopedEnvironment, resourceKey.trim(), effectiveLimit);
        if (resolved.isEmpty() && shouldFallbackToCatalogScope(scopedTenant, scopedEnvironment)) {
            resolved = resolveExactScope(
                    effectiveCatalogFallbackTenantId(scopedTenant),
                    effectiveCatalogFallbackEnvironment(scopedEnvironment),
                    resourceKey.trim(),
                    effectiveLimit);
        }
        return resolved;
    }

    private List<BindingProjection> resolveExactScope(
            String scopedTenant,
            String scopedEnvironment,
            String resourceKey,
            int effectiveLimit) {
        return bindingRepository.findGovernedOperationalBindings(
                        scopedTenant, scopedEnvironment, resourceKey).stream()
                .filter(binding -> belongsToScope(binding, scopedTenant, scopedEnvironment))
                .filter(this::releaseIsCurrentForConcept)
                .filter(this::hasActiveConceptEvidence)
                .map(this::projection)
                .limit(effectiveLimit)
                .toList();
    }

    private boolean shouldFallbackToCatalogScope(String tenantId, String environment) {
        if (!StringUtils.hasText(catalogFallbackTenantId) && !StringUtils.hasText(catalogFallbackEnvironment)) {
            return false;
        }
        String effectiveTenant = effectiveCatalogFallbackTenantId(tenantId);
        String effectiveEnv = effectiveCatalogFallbackEnvironment(environment);
        return !Objects.equals(tenantId, effectiveTenant) || !Objects.equals(environment, effectiveEnv);
    }

    private String effectiveCatalogFallbackTenantId(String tenantId) {
        return StringUtils.hasText(catalogFallbackTenantId) ? catalogFallbackTenantId : tenantId;
    }

    private String effectiveCatalogFallbackEnvironment(String environment) {
        return StringUtils.hasText(catalogFallbackEnvironment) ? catalogFallbackEnvironment : environment;
    }

    private boolean belongsToScope(
            DomainKnowledgeBinding binding,
            String tenantId,
            String environment) {
        return binding != null
                && Objects.equals(tenantId, binding.getTenantId())
                && Objects.equals(environment, binding.getEnvironment())
                && binding.getConcept() != null
                && Objects.equals(tenantId, binding.getConcept().getTenantId())
                && Objects.equals(environment, binding.getConcept().getEnvironment());
    }

    private boolean releaseIsCurrentForConcept(DomainKnowledgeBinding binding) {
        if (binding == null || binding.getConcept() == null) {
            return false;
        }
        if (binding.getSourceRelease() == null || binding.getConcept().getSourceRelease() == null) {
            return true;
        }
        return Objects.equals(
                binding.getConcept().getSourceRelease().getId(),
                binding.getSourceRelease().getId());
    }

    private boolean hasActiveConceptEvidence(DomainKnowledgeBinding binding) {
        return binding != null
                && binding.getConcept() != null
                && binding.getConcept().getId() != null
                && !evidenceRepository.findByTenantIdAndEnvironmentAndSubjectTypeAndSubjectIdAndStatus(
                        binding.getTenantId(),
                        binding.getEnvironment(),
                        "concept",
                        binding.getConcept().getId(),
                        "active").isEmpty();
    }

    private BindingProjection projection(DomainKnowledgeBinding binding) {
        return new BindingProjection(
                binding.getConcept().getConceptKey(),
                binding.getBindingType(),
                binding.getBindingKey(),
                operationId(binding),
                binding.getResourceKey(),
                binding.getApiPath(),
                binding.getApiMethod(),
                binding.getSchemaPointer(),
                binding.getConfidence(),
                binding.getSourceRelease() == null ? null : binding.getSourceRelease().getReleaseKey(),
                List.of(
                        "domain-knowledge:binding:" + binding.getBindingKey(),
                        "domain-knowledge:concept:" + binding.getConcept().getConceptKey(),
                        "domain-knowledge:evidence-status:active"));
    }

    private String operationId(DomainKnowledgeBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.getPayload())) {
            return "";
        }
        try {
            JsonNode target = objectMapper.readTree(binding.getPayload()).path("target");
            String actionId = target.path("id").asText("").trim();
            if ("workflow_action".equals(binding.getBindingType()) && StringUtils.hasText(actionId)) {
                return actionId;
            }
            return target.path("operationId").asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    record BindingProjection(
            String conceptKey,
            String bindingType,
            String bindingKey,
            String operationId,
            String resourceKey,
            String apiPath,
            String apiMethod,
            String schemaPointer,
            Double confidence,
            String sourceRelease,
            List<String> evidence) {

        BindingProjection(
                String conceptKey,
                String bindingType,
                String bindingKey,
                String resourceKey,
                String apiPath,
                String apiMethod,
                String schemaPointer,
                Double confidence,
                String sourceRelease,
                List<String> evidence) {
            this(
                    conceptKey,
                    bindingType,
                    bindingKey,
                    "",
                    resourceKey,
                    apiPath,
                    apiMethod,
                    schemaPointer,
                    confidence,
                    sourceRelease,
                    evidence);
        }
    }
}
