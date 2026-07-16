package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** Canonical approval manifest and its immutable SHA-256 digest. */
@Schema(description = "Server-canonicalized RuleSet composition that approvers must review and approve by exact digest.")
public record DomainRuleCompositionManifestResponse(
    @Schema(description = "Version of the canonical composition-manifest contract.")
    String compositionContractVersion,
    @Schema(description = "SHA-256 of the complete canonical manifest; this exact value is the approval evidence hash.")
    String compositionDigest,
    @Schema(description = "SHA-256 of the host-governed Java implementation admission catalog included in the manifest.")
    String implementationCatalogDigest,
    @Schema(description = "Canonical review document containing scope, validity, sources, catalog and complete RuleSet.")
    JsonNode manifest) {}
