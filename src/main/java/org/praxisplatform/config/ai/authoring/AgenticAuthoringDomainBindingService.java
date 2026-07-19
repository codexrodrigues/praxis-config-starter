package org.praxisplatform.config.ai.authoring;

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

    public AgenticAuthoringDomainBindingService(
            DomainKnowledgeBindingRepository bindingRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository) {
        this.bindingRepository = bindingRepository;
        this.evidenceRepository = evidenceRepository;
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
        return bindingRepository.findGovernedOperationalBindings(
                        scopedTenant, scopedEnvironment, resourceKey.trim()).stream()
                .filter(binding -> belongsToScope(binding, scopedTenant, scopedEnvironment))
                .filter(this::releaseIsCurrentForConcept)
                .filter(this::hasActiveConceptEvidence)
                .map(this::projection)
                .limit(effectiveLimit)
                .toList();
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

    record BindingProjection(
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
    }
}
