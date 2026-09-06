package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.praxisplatform.config.dto.GovernedColorPalettePreviewResponse;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolves published palette materializations without creating parallel persistence. */
@Service
@RequiredArgsConstructor
@ConditionalOnBean(DomainRuleMaterializationRepository.class)
public class GovernedColorPaletteProjectionService {

    public static final String RULE_TYPE = "design_token_palette";
    public static final String TARGET_LAYER = "design_token_catalog";
    public static final String TARGET_ARTIFACT_TYPE = "governed-color-palette";

    private final DomainRuleMaterializationRepository materializationRepository;
    private final ObjectMapper objectMapper;
    private final GovernedColorPaletteContractValidator contractValidator;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<GovernedColorPaletteResponse> list(String tenantId, String environment) {
        return list(tenantId, environment, null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<GovernedColorPaletteResponse> list(
            String tenantId,
            String environment,
            String familyKey) {
        String normalizedFamilyKey = normalize(familyKey);
        return materializationRepository
                .findByTenantIdAndEnvironmentAndStatus(normalize(tenantId), normalize(environment), "applied")
                .stream()
                .filter(this::isPaletteMaterialization)
                .collect(java.util.stream.Collectors.toMap(
                        DomainRuleMaterialization::getTargetArtifactKey,
                        item -> item,
                        (left, right) -> left.getRuleDefinition().getVersion() >= right.getRuleDefinition().getVersion()
                                ? left : right))
                .values().stream()
                .map(this::toResponse)
                .filter(palette -> normalizedFamilyKey == null
                        || normalizedFamilyKey.equals(palette.familyKey()))
                .sorted(Comparator
                        .comparing(GovernedColorPaletteResponse::familyKey)
                        .thenComparing(palette -> palette.variant().key())
                        .thenComparing(GovernedColorPaletteResponse::paletteKey))
                .toList();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public GovernedColorPaletteResponse get(String tenantId, String environment, String paletteKey) {
        return get(tenantId, environment, paletteKey, null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public GovernedColorPaletteResponse get(
            String tenantId,
            String environment,
            String paletteKey,
            Integer version) {
        if (paletteKey == null || paletteKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paletteKey is required");
        }
        if (version != null && version < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version must be greater than zero");
        }
        return materializationRepository
                .findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
                        normalize(tenantId),
                        normalize(environment),
                        TARGET_LAYER,
                        TARGET_ARTIFACT_TYPE,
                        paletteKey.trim())
                .stream()
                .filter(item -> "applied".equals(item.getStatus())
                        || version != null && "superseded".equals(item.getStatus()) && item.getAppliedAt() != null)
                .filter(this::isPaletteMaterialization)
                .filter(item -> version == null || Objects.equals(item.getRuleDefinition().getVersion(), version))
                .max(Comparator.comparing(item -> item.getRuleDefinition().getVersion()))
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        version == null
                                ? "Published color palette not found: " + paletteKey
                                : "Published color palette not found: " + paletteKey + " version " + version));
    }

    /** Produces the exact normalized projection and diagnostics without persisting authoring state. */
    public GovernedColorPalettePreviewResponse preview(String paletteKey, JsonNode payload) {
        GovernedColorPaletteContractValidator.Result result = contractValidator.validate(paletteKey, payload);
        return new GovernedColorPalettePreviewResponse(
                RULE_TYPE,
                TARGET_LAYER,
                TARGET_ARTIFACT_TYPE,
                result.paletteKey(),
                result.displayName(),
                result.familyKey(),
                result.variant(),
                result.scope(),
                result.entries(),
                result.contrastEvidence(),
                result.provenance(),
                result.validation());
    }

    public Set<String> supportedPurposes() {
        return contractValidator.supportedPurposes();
    }

    private boolean isPaletteMaterialization(DomainRuleMaterialization materialization) {
        DomainRuleDefinition definition = materialization.getRuleDefinition();
        return TARGET_LAYER.equals(materialization.getTargetLayer())
                && TARGET_ARTIFACT_TYPE.equals(materialization.getTargetArtifactType())
                && definition != null
                && "active".equals(definition.getStatus())
                && RULE_TYPE.equals(definition.getRuleType());
    }

    private GovernedColorPaletteResponse toResponse(DomainRuleMaterialization materialization) {
        JsonNode payload = readObject(materialization.getMaterializedPayload());
        String paletteKey = materialization.getTargetArtifactKey();
        GovernedColorPaletteContractValidator.Result result = contractValidator.validate(paletteKey, payload);

        DomainRuleDefinition definition = materialization.getRuleDefinition();
        String etag = materialization.getSourceHash();
        if (etag == null || etag.isBlank()) {
            etag = sha256(definition.getId() + ":" + definition.getVersion() + ":" + payload);
        }
        return new GovernedColorPaletteResponse(
                paletteKey,
                result.displayName(),
                result.familyKey(),
                result.variant(),
                definition.getVersion(),
                "published",
                materialization.getTenantId(),
                materialization.getEnvironment(),
                result.scope(),
                result.entries(),
                result.contrastEvidence(),
                result.provenance(),
                result.validation(),
                materialization.getAppliedAt(),
                etag);
    }

    private JsonNode readObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value == null ? "{}" : value);
            for (int depth = 0; depth < 8 && node != null && node.isTextual(); depth++) {
                String nested = node.textValue();
                if (nested == null || nested.isBlank()) {
                    return objectMapper.createObjectNode();
                }
                String trimmed = nested.trim();
                if (!(trimmed.startsWith("{") || trimmed.startsWith("\""))) {
                    break;
                }
                node = objectMapper.readTree(trimmed);
            }
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
