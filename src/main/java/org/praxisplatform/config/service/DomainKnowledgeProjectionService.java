package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeAlias;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.domain.DomainKnowledgeRelationship;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainKnowledgeAliasRepository;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.repository.DomainKnowledgeRelationshipRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnBean({
        DomainKnowledgeConceptRepository.class,
        DomainKnowledgeAliasRepository.class,
        DomainKnowledgeBindingRepository.class,
        DomainKnowledgeRelationshipRepository.class,
        DomainKnowledgeEvidenceRepository.class
})
@ConditionalOnProperty(
        prefix = "praxis.domain-knowledge.projection",
        name = "enabled",
        havingValue = "true")
public class DomainKnowledgeProjectionService {

    private final DomainKnowledgeConceptRepository conceptRepository;
    private final DomainKnowledgeAliasRepository aliasRepository;
    private final DomainKnowledgeBindingRepository bindingRepository;
    private final DomainKnowledgeRelationshipRepository relationshipRepository;
    private final DomainKnowledgeEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void project(DomainCatalogRelease release, List<DomainCatalogItem> items) {
        if (release == null || items == null || items.isEmpty()) {
            return;
        }
        Map<String, DomainKnowledgeConcept> conceptsByKey = projectConcepts(release, items);
        projectAliases(release, items, conceptsByKey);
        projectBindings(release, items, conceptsByKey);
        projectRelationships(release, items, conceptsByKey);
        projectEvidence(release, items, conceptsByKey);
    }

    private Map<String, DomainKnowledgeConcept> projectConcepts(DomainCatalogRelease release, List<DomainCatalogItem> items) {
        Map<String, DomainKnowledgeConcept> conceptsByKey = new LinkedHashMap<>();
        for (DomainCatalogItem item : items) {
            if (!"node".equals(item.getItemType())) {
                continue;
            }
            JsonNode payload = read(item.getPayload());
            String conceptKey = firstText(item.getItemKey(), text(payload, "nodeKey"));
            if (!StringUtils.hasText(conceptKey)) {
                continue;
            }
            DomainKnowledgeConcept concept = conceptRepository
                    .findByTenantIdAndEnvironmentAndConceptKey(release.getTenantId(), release.getEnvironment(), conceptKey)
                    .orElseGet(DomainKnowledgeConcept::new);
            concept.setTenantId(release.getTenantId());
            concept.setEnvironment(release.getEnvironment());
            concept.setConceptKey(conceptKey);
            concept.setContextKey(firstText(item.getContextKey(), text(payload, "contextKey")));
            concept.setResourceKey(firstText(text(payload, "resourceKey"), resourceKeyFromReleaseKey(release.getReleaseKey())));
            concept.setNodeType(firstText(item.getNodeType(), text(payload, "nodeType"), "concept"));
            concept.setLabel(text(payload, "label"));
            concept.setDescription(text(payload, "description"));
            concept.setLocale(text(payload, "locale"));
            concept.setSemanticOwner(text(payload, "semanticOwner"));
            concept.setSteward(text(payload, "steward"));
            concept.setAiVisibility(aiVisibility(payload));
            concept.setDataCategory(text(payload, "dataCategory"));
            concept.setClassification(text(payload, "classification"));
            concept.setComplianceTags(arrayJson(payload.path("complianceTags")));
            concept.setSourceRelease(release);
            concept.setPayload(item.getPayload());
            conceptsByKey.put(conceptKey, concept);
        }
        if (!conceptsByKey.isEmpty()) {
            for (DomainKnowledgeConcept concept : conceptRepository.saveAll(new ArrayList<>(conceptsByKey.values()))) {
                conceptsByKey.put(concept.getConceptKey(), concept);
            }
        }
        return conceptsByKey;
    }

    private void projectAliases(
            DomainCatalogRelease release,
            List<DomainCatalogItem> items,
            Map<String, DomainKnowledgeConcept> conceptsByKey) {
        Map<String, DomainKnowledgeAlias> aliasesByConceptAndValue = existingAliasesByConceptAndValue(conceptsByKey);
        Map<String, DomainKnowledgeAlias> projectedAliasesByKey = new LinkedHashMap<>();
        for (DomainCatalogItem item : items) {
            if (!"alias".equals(item.getItemType())) {
                continue;
            }
            JsonNode payload = read(item.getPayload());
            DomainKnowledgeConcept concept = conceptFor(payload, conceptsByKey);
            String alias = text(payload, "alias");
            if (concept == null || !StringUtils.hasText(alias)) {
                continue;
            }
            String normalizedAlias = normalizedAlias(alias);
            String lookupKey = aliasLookupKey(concept.getId(), normalizedAlias);
            DomainKnowledgeAlias projected = aliasesByConceptAndValue
                    .getOrDefault(lookupKey, new DomainKnowledgeAlias());
            projected.setTenantId(release.getTenantId());
            projected.setEnvironment(release.getEnvironment());
            projected.setConcept(concept);
            projected.setAlias(alias);
            projected.setNormalizedAlias(normalizedAlias);
            projected.setLocale(text(payload, "locale"));
            projected.setAliasType(aliasType(payload));
            projected.setSource("generated");
            projected.setWeight(confidence(payload, 1.0));
            aliasesByConceptAndValue.put(lookupKey, projected);
            projectedAliasesByKey.put(lookupKey, projected);
        }
        if (!projectedAliasesByKey.isEmpty()) {
            aliasRepository.saveAll(new ArrayList<>(projectedAliasesByKey.values()));
        }
    }

    private void projectBindings(
            DomainCatalogRelease release,
            List<DomainCatalogItem> items,
            Map<String, DomainKnowledgeConcept> conceptsByKey) {
        Map<String, DomainKnowledgeBinding> bindingsByTypeAndKey = existingBindingsByTypeAndKey(release, items);
        Map<String, DomainKnowledgeBinding> projectedBindingsByKey = new LinkedHashMap<>();
        for (DomainCatalogItem item : items) {
            if (!"binding".equals(item.getItemType())) {
                continue;
            }
            JsonNode payload = read(item.getPayload());
            DomainKnowledgeConcept concept = conceptFor(payload, conceptsByKey);
            String bindingType = firstText(item.getBindingType(), text(payload, "bindingType"));
            String bindingKey = firstText(item.getItemKey(), text(payload, "bindingKey"));
            if (concept == null || !StringUtils.hasText(bindingType) || !StringUtils.hasText(bindingKey)) {
                continue;
            }
            String lookupKey = bindingLookupKey(bindingType, bindingKey);
            DomainKnowledgeBinding binding = bindingsByTypeAndKey
                    .getOrDefault(lookupKey, new DomainKnowledgeBinding());
            binding.setTenantId(release.getTenantId());
            binding.setEnvironment(release.getEnvironment());
            binding.setConcept(concept);
            binding.setBindingType(bindingType);
            binding.setBindingKey(bindingKey);
            binding.setResourceKey(firstText(text(payload, "resourceKey"), concept.getResourceKey()));
            binding.setApiPath(firstText(text(payload, "apiPath"), text(payload.path("target"), "apiPath")));
            binding.setApiMethod(firstText(text(payload, "apiMethod"), text(payload.path("target"), "apiMethod")));
            binding.setSchemaPointer(firstText(text(payload, "schemaPointer"), schemaPointer(payload.path("target"))));
            binding.setSourceRelease(release);
            binding.setConfidence(confidence(payload, null));
            binding.setPayload(item.getPayload());
            bindingsByTypeAndKey.put(lookupKey, binding);
            projectedBindingsByKey.put(lookupKey, binding);
        }
        if (!projectedBindingsByKey.isEmpty()) {
            bindingRepository.saveAll(new ArrayList<>(projectedBindingsByKey.values()));
        }
    }

    private void projectRelationships(
            DomainCatalogRelease release,
            List<DomainCatalogItem> items,
            Map<String, DomainKnowledgeConcept> conceptsByKey) {
        Map<String, DomainKnowledgeRelationship> relationshipsBySourceTargetAndType =
                existingRelationshipsBySourceTargetAndType(release, conceptsByKey);
        Map<String, DomainKnowledgeRelationship> projectedRelationshipsByKey = new LinkedHashMap<>();
        for (DomainCatalogItem item : items) {
            if (!"edge".equals(item.getItemType())) {
                continue;
            }
            JsonNode payload = read(item.getPayload());
            DomainKnowledgeConcept source = conceptsByKey.get(text(payload, "sourceNodeKey"));
            DomainKnowledgeConcept target = conceptsByKey.get(text(payload, "targetNodeKey"));
            String relationshipType = firstText(item.getEdgeType(), text(payload, "edgeType"));
            if (source == null || target == null || !StringUtils.hasText(relationshipType)) {
                continue;
            }
            String lookupKey = relationshipLookupKey(source.getId(), target.getId(), relationshipType);
            DomainKnowledgeRelationship relationship = relationshipsBySourceTargetAndType
                    .getOrDefault(lookupKey, new DomainKnowledgeRelationship());
            relationship.setTenantId(release.getTenantId());
            relationship.setEnvironment(release.getEnvironment());
            relationship.setSourceConcept(source);
            relationship.setTargetConcept(target);
            relationship.setRelationshipType(relationshipType);
            relationship.setCrossContext(!Objects.equals(source.getContextKey(), target.getContextKey()));
            relationship.setSourceContextKey(source.getContextKey());
            relationship.setTargetContextKey(target.getContextKey());
            relationship.setContractKey(firstText(text(payload, "contractKey"), item.getItemKey()));
            relationship.setConfidence(confidence(payload, null));
            relationship.setPayload(item.getPayload());
            relationshipsBySourceTargetAndType.put(lookupKey, relationship);
            projectedRelationshipsByKey.put(lookupKey, relationship);
        }
        if (!projectedRelationshipsByKey.isEmpty()) {
            relationshipRepository.saveAll(new ArrayList<>(projectedRelationshipsByKey.values()));
        }
    }

    private Map<String, DomainKnowledgeAlias> existingAliasesByConceptAndValue(
            Map<String, DomainKnowledgeConcept> conceptsByKey) {
        List<UUID> conceptIds = conceptIds(conceptsByKey);
        Map<String, DomainKnowledgeAlias> aliasesByConceptAndValue = new LinkedHashMap<>();
        if (conceptIds.isEmpty()) {
            return aliasesByConceptAndValue;
        }
        for (DomainKnowledgeAlias alias : aliasRepository.findByConcept_IdIn(conceptIds)) {
            if (alias.getConcept() == null || alias.getConcept().getId() == null
                    || !StringUtils.hasText(alias.getNormalizedAlias())) {
                continue;
            }
            aliasesByConceptAndValue.putIfAbsent(
                    aliasLookupKey(alias.getConcept().getId(), alias.getNormalizedAlias()),
                    alias);
        }
        return aliasesByConceptAndValue;
    }

    private Map<String, DomainKnowledgeRelationship> existingRelationshipsBySourceTargetAndType(
            DomainCatalogRelease release,
            Map<String, DomainKnowledgeConcept> conceptsByKey) {
        List<UUID> conceptIds = conceptIds(conceptsByKey);
        Map<String, DomainKnowledgeRelationship> relationshipsBySourceTargetAndType = new LinkedHashMap<>();
        if (conceptIds.isEmpty()) {
            return relationshipsBySourceTargetAndType;
        }
        for (DomainKnowledgeRelationship relationship : relationshipRepository
                .findByTenantIdAndEnvironmentAndSourceConcept_IdIn(
                        release.getTenantId(),
                        release.getEnvironment(),
                        conceptIds)) {
            if (relationship.getSourceConcept() == null || relationship.getSourceConcept().getId() == null
                    || relationship.getTargetConcept() == null || relationship.getTargetConcept().getId() == null
                    || !StringUtils.hasText(relationship.getRelationshipType())) {
                continue;
            }
            relationshipsBySourceTargetAndType.putIfAbsent(
                    relationshipLookupKey(
                            relationship.getSourceConcept().getId(),
                            relationship.getTargetConcept().getId(),
                            relationship.getRelationshipType()),
                    relationship);
        }
        return relationshipsBySourceTargetAndType;
    }

    private Map<String, DomainKnowledgeBinding> existingBindingsByTypeAndKey(
            DomainCatalogRelease release,
            List<DomainCatalogItem> items) {
        List<String> bindingKeys = new ArrayList<>();
        for (DomainCatalogItem item : items) {
            if (!"binding".equals(item.getItemType())) {
                continue;
            }
            JsonNode payload = read(item.getPayload());
            String bindingKey = firstText(item.getItemKey(), text(payload, "bindingKey"));
            if (StringUtils.hasText(bindingKey)) {
                bindingKeys.add(bindingKey);
            }
        }
        Map<String, DomainKnowledgeBinding> bindingsByTypeAndKey = new LinkedHashMap<>();
        if (bindingKeys.isEmpty()) {
            return bindingsByTypeAndKey;
        }
        for (DomainKnowledgeBinding binding : bindingRepository.findByTenantIdAndEnvironmentAndBindingKeyIn(
                release.getTenantId(),
                release.getEnvironment(),
                bindingKeys)) {
            if (!StringUtils.hasText(binding.getBindingType()) || !StringUtils.hasText(binding.getBindingKey())) {
                continue;
            }
            bindingsByTypeAndKey.putIfAbsent(
                    bindingLookupKey(binding.getBindingType(), binding.getBindingKey()),
                    binding);
        }
        return bindingsByTypeAndKey;
    }

    private List<UUID> conceptIds(Map<String, DomainKnowledgeConcept> conceptsByKey) {
        List<UUID> conceptIds = new ArrayList<>();
        for (DomainKnowledgeConcept concept : conceptsByKey.values()) {
            if (concept != null && concept.getId() != null) {
                conceptIds.add(concept.getId());
            }
        }
        return conceptIds;
    }

    private String aliasLookupKey(UUID conceptId, String normalizedAlias) {
        return conceptId + "::" + normalizedAlias;
    }

    private String relationshipLookupKey(UUID sourceConceptId, UUID targetConceptId, String relationshipType) {
        return sourceConceptId + "::" + targetConceptId + "::" + relationshipType;
    }

    private String bindingLookupKey(String bindingType, String bindingKey) {
        return bindingType + "::" + bindingKey;
    }

    private void projectEvidence(
            DomainCatalogRelease release,
            List<DomainCatalogItem> items,
            Map<String, DomainKnowledgeConcept> conceptsByKey) {
        Map<String, DomainKnowledgeConcept> conceptsByEvidenceKey = conceptsByEvidenceKey(items, conceptsByKey);
        Map<String, DomainKnowledgeEvidence> evidenceByKey = existingEvidenceByKey(release, items);
        Map<String, DomainKnowledgeEvidence> projectedEvidenceByKey = new LinkedHashMap<>();
        for (DomainCatalogItem item : items) {
            if (!"node".equals(item.getItemType()) && !"evidence".equals(item.getItemType())) {
                continue;
            }
            JsonNode payload = read(item.getPayload());
            DomainKnowledgeConcept concept = "node".equals(item.getItemType())
                    ? conceptsByKey.get(item.getItemKey())
                    : firstConcept(conceptFor(payload, conceptsByKey), conceptsByEvidenceKey.get(firstText(item.getItemKey(), text(payload, "evidenceKey"))));
            if (concept == null) {
                continue;
            }
            String evidenceKey = "node".equals(item.getItemType())
                    ? "catalog-release:" + release.getReleaseKey() + ":" + item.getItemKey()
                    : firstText(item.getItemKey(), text(payload, "evidenceKey"));
            DomainKnowledgeEvidence evidence = evidenceByKey
                    .getOrDefault(evidenceKey, new DomainKnowledgeEvidence());
            evidence.setTenantId(release.getTenantId());
            evidence.setEnvironment(release.getEnvironment());
            evidence.setEvidenceKey(evidenceKey);
            evidence.setSubjectType("concept");
            evidence.setSubjectId(concept.getId());
            evidence.setEvidenceType(evidenceType(payload, item.getItemType()));
            evidence.setSourceRelease(release);
            evidence.setSourcePointer(sourcePointer(item));
            evidence.setConfidence(confidence(payload, 1.0));
            evidence.setPayload(item.getPayload());
            evidenceByKey.put(evidenceKey, evidence);
            projectedEvidenceByKey.put(evidenceKey, evidence);
        }
        if (!projectedEvidenceByKey.isEmpty()) {
            evidenceRepository.saveAll(new ArrayList<>(projectedEvidenceByKey.values()));
        }
    }

    private Map<String, DomainKnowledgeEvidence> existingEvidenceByKey(
            DomainCatalogRelease release,
            List<DomainCatalogItem> items) {
        List<String> evidenceKeys = new ArrayList<>();
        for (DomainCatalogItem item : items) {
            if (!"node".equals(item.getItemType()) && !"evidence".equals(item.getItemType())) {
                continue;
            }
            JsonNode payload = read(item.getPayload());
            String evidenceKey = "node".equals(item.getItemType())
                    ? "catalog-release:" + release.getReleaseKey() + ":" + item.getItemKey()
                    : firstText(item.getItemKey(), text(payload, "evidenceKey"));
            if (StringUtils.hasText(evidenceKey)) {
                evidenceKeys.add(evidenceKey);
            }
        }
        Map<String, DomainKnowledgeEvidence> evidenceByKey = new LinkedHashMap<>();
        if (evidenceKeys.isEmpty()) {
            return evidenceByKey;
        }
        for (DomainKnowledgeEvidence evidence : evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKeyIn(
                release.getTenantId(),
                release.getEnvironment(),
                evidenceKeys)) {
            if (StringUtils.hasText(evidence.getEvidenceKey())) {
                evidenceByKey.putIfAbsent(evidence.getEvidenceKey(), evidence);
            }
        }
        return evidenceByKey;
    }

    private Map<String, DomainKnowledgeConcept> conceptsByEvidenceKey(
            List<DomainCatalogItem> items,
            Map<String, DomainKnowledgeConcept> conceptsByKey) {
        Map<String, DomainKnowledgeConcept> conceptsByEvidenceKey = new LinkedHashMap<>();
        for (DomainCatalogItem item : items) {
            JsonNode payload = read(item.getPayload());
            DomainKnowledgeConcept concept = conceptFor(payload, conceptsByKey);
            if ("node".equals(item.getItemType())) {
                concept = conceptsByKey.get(item.getItemKey());
            } else if ("edge".equals(item.getItemType())) {
                concept = conceptsByKey.get(text(payload, "sourceNodeKey"));
            }
            if (concept == null) {
                continue;
            }
            for (JsonNode evidenceKey : payload.path("evidenceKeys")) {
                if (evidenceKey != null && evidenceKey.isTextual() && StringUtils.hasText(evidenceKey.asText())) {
                    conceptsByEvidenceKey.putIfAbsent(evidenceKey.asText().trim(), concept);
                }
            }
            for (JsonNode evidenceKey : payload.path("sourceEvidenceKeys")) {
                if (evidenceKey != null && evidenceKey.isTextual() && StringUtils.hasText(evidenceKey.asText())) {
                    conceptsByEvidenceKey.putIfAbsent(evidenceKey.asText().trim(), concept);
                }
            }
        }
        return conceptsByEvidenceKey;
    }

    private DomainKnowledgeConcept conceptFor(JsonNode payload, Map<String, DomainKnowledgeConcept> conceptsByKey) {
        return conceptsByKey.get(firstText(text(payload, "nodeKey"), text(payload, "subjectNodeKey")));
    }

    private DomainKnowledgeConcept firstConcept(DomainKnowledgeConcept... concepts) {
        for (DomainKnowledgeConcept concept : concepts) {
            if (concept != null) {
                return concept;
            }
        }
        return null;
    }

    private String sourcePointer(DomainCatalogItem item) {
        return "/" + item.getItemType() + "s/" + item.getItemKey();
    }

    private String schemaPointer(JsonNode target) {
        String schemaId = text(target, "schemaId");
        String fieldName = text(target, "fieldName");
        if (StringUtils.hasText(schemaId) && StringUtils.hasText(fieldName)) {
            return schemaId + "#/" + fieldName;
        }
        return StringUtils.hasText(schemaId) ? schemaId : null;
    }

    private String aliasType(JsonNode payload) {
        String source = text(payload, "source");
        if (source != null && source.contains("schema")) {
            return "technical_name";
        }
        return "synonym";
    }

    private String evidenceType(JsonNode payload, String itemType) {
        if ("node".equals(itemType)) {
            return "catalog_release";
        }
        String type = text(payload, "evidenceType");
        if (type == null) {
            return "catalog_release";
        }
        if (type.contains("schema")) {
            return "json_schema";
        }
        if (type.contains("annotation")) {
            return "annotation";
        }
        return "catalog_release";
    }

    private Double confidence(JsonNode payload, Double fallback) {
        JsonNode confidence = payload == null ? null : payload.path("confidence");
        if (confidence != null && confidence.isNumber()) {
            return confidence.asDouble();
        }
        return fallback;
    }

    private String aiVisibility(JsonNode payload) {
        String visibility = text(payload.path("aiUsage"), "visibility");
        return StringUtils.hasText(visibility) ? visibility.toLowerCase(Locale.ROOT) : "allow";
    }

    private String arrayJson(JsonNode node) {
        return node != null && node.isArray() ? node.toString() : null;
    }

    private String normalizedAlias(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private String resourceKeyFromReleaseKey(String releaseKey) {
        if (!StringUtils.hasText(releaseKey)) {
            return null;
        }
        String[] parts = releaseKey.split(":", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private JsonNode read(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to read domain catalog item payload", ex);
        }
    }
}
