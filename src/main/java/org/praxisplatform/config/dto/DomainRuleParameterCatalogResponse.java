package org.praxisplatform.config.dto;

import java.util.List;
import java.util.UUID;

/** Governed parameter catalog; absence of a template is represented by an empty catalog. */
public record DomainRuleParameterCatalogResponse(
    UUID definitionId, String ruleKey, Integer definitionVersion, String schemaVersion,
    String templateKey, String templateVersion, List<DomainRuleParameterDescriptor> parameters) {}
