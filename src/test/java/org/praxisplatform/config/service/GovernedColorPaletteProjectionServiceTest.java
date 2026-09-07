package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class GovernedColorPaletteProjectionServiceTest {

    @Mock private DomainRuleMaterializationRepository repository;

    private GovernedColorPaletteProjectionService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new GovernedColorPaletteProjectionService(
                repository, objectMapper, new GovernedColorPaletteContractValidator(objectMapper));
    }

    @Test
    void previewsTheExactGovernedTargetWithoutPersistingIt() throws Exception {
        var result = service.preview("corporate-main", new ObjectMapper().readTree(validPayload()));

        assertThat(result.ruleType()).isEqualTo("design_token_palette");
        assertThat(result.targetLayer()).isEqualTo("design_token_catalog");
        assertThat(result.targetArtifactType()).isEqualTo("governed-color-palette");
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.entries()).hasSize(2);
    }

    @Test
    void resolvesPublishedPaletteWithCalculatedContrastEvidence() {
        DomainRuleMaterialization materialization = materialization("corporate-main", validPayload(), 3);
        when(repository.findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
                "acme", "prod", "design_token_catalog", "governed-color-palette", "corporate-main"))
                .thenReturn(List.of(materialization));

        var result = service.get("acme", "prod", "corporate-main");

        assertThat(result.paletteKey()).isEqualTo("corporate-main");
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.contrastEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.ratio()).isGreaterThan(5d);
            assertThat(evidence.passes()).isTrue();
        });
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.familyKey()).isEqualTo("acme.corporate");
        assertThat(result.variant().key()).isEqualTo("light");
        assertThat(result.entries().getFirst().displayName()).isEqualTo("Primary brand");
        assertThat(result.entries().getFirst().aliases()).containsExactly("Main brand");
        assertThat(result.etag()).startsWith("sha256:");
    }

    @Test
    void changesTheDerivedEtagWhenPublishedTokenPresentationChanges() {
        var first = materialization("corporate-main", validPayload(), 3);
        var renamed = materialization(
                "corporate-main", validPayload().replace("Main brand", "Primary action"), 3);
        renamed.getRuleDefinition().setId(first.getRuleDefinition().getId());
        when(repository.findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
                "acme", "prod", "design_token_catalog", "governed-color-palette", "corporate-main"))
                .thenReturn(List.of(first))
                .thenReturn(List.of(renamed));

        String firstEtag = service.get("acme", "prod", "corporate-main").etag();
        String renamedEtag = service.get("acme", "prod", "corporate-main").etag();
        assertThat(renamedEtag).isNotEqualTo(firstEtag);
    }

    @Test
    void listIgnoresAppliedMaterializationsOwnedByOtherRuleTypes() {
        DomainRuleMaterialization palette = materialization("corporate-main", validPayload(), 1);
        DomainRuleMaterialization unrelated = materialization("other", validPayload(), 1);
        unrelated.getRuleDefinition().setRuleType("selection_eligibility");
        when(repository.findByTenantIdAndEnvironmentAndStatus("acme", "prod", "applied"))
                .thenReturn(List.of(unrelated, palette));

        assertThat(service.list("acme", "prod"))
                .extracting(item -> item.paletteKey())
                .containsExactly("corporate-main");
    }

    @Test
    void resolvesAnExplicitPublishedVersionForDeterministicConsumers() {
        DomainRuleMaterialization versionThree = materialization("corporate-main", validPayload(), 3);
        DomainRuleMaterialization versionFour = materialization("corporate-main", validPayload(), 4);
        when(repository.findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
                "acme", "prod", "design_token_catalog", "governed-color-palette", "corporate-main"))
                .thenReturn(List.of(versionThree, versionFour));

        var result = service.get("acme", "prod", "corporate-main", 3);

        assertThat(result.version()).isEqualTo(3);
    }

    @Test
    void resolvesVersionOutsideIntegerCache() {
        when(repository.findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
                "acme", "prod", "design_token_catalog", "governed-color-palette", "corporate-main"))
                .thenReturn(List.of(materialization("corporate-main", validPayload(), 128)));
        assertThat(service.get("acme", "prod", "corporate-main", Integer.valueOf("128")).version())
                .isEqualTo(128);
    }

    @Test
    void listsOnlyTheLatestAppliedVersionOfEachPalette() {
        when(repository.findByTenantIdAndEnvironmentAndStatus("acme", "prod", "applied"))
                .thenReturn(List.of(
                        materialization("corporate-main", validPayload(), 1),
                        materialization("corporate-main", validPayload(), 2)));
        assertThat(service.list("acme", "prod")).singleElement()
                .satisfies(palette -> assertThat(palette.version()).isEqualTo(2));
    }

    @Test
    void filtersAnExactFamilyAndOrdersVariantsDeterministically() {
        var dark = materialization(
                "corporate-dark",
                validPayload().replace("corporate-main", "corporate-dark")
                        .replace("\"key\":\"light\"", "\"key\":\"dark\"")
                        .replace("\"displayName\":\"Light\"", "\"displayName\":\"Dark\""),
                1);
        var contrast = materialization(
                "corporate-contrast",
                validPayload().replace("corporate-main", "corporate-contrast")
                        .replace("\"key\":\"light\"", "\"key\":\"high-contrast\"")
                        .replace("\"displayName\":\"Light\"", "\"displayName\":\"High contrast\""),
                1);
        var other = materialization(
                "partner-light",
                validPayload().replace("corporate-main", "partner-light")
                        .replace("acme.corporate", "acme.partner"),
                1);
        when(repository.findByTenantIdAndEnvironmentAndStatus("acme", "prod", "applied"))
                .thenReturn(List.of(other, contrast, dark));

        assertThat(service.list("acme", "prod", "acme.corporate"))
                .extracting(item -> item.variant().key())
                .containsExactly("dark", "high-contrast");
    }

    @Test
    void pinnedHistoryRequiresPreviousApplicationAndAnActiveSourceDefinition() {
        var old = materialization("corporate-main", validPayload(), 1);
        old.setStatus("superseded");
        when(repository.findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
                "acme", "prod", "design_token_catalog", "governed-color-palette", "corporate-main"))
                .thenReturn(List.of(old));
        assertThat(service.get("acme", "prod", "corporate-main", 1).version()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get("acme", "prod", "corporate-main"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        old.getRuleDefinition().setStatus("retired");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get("acme", "prod", "corporate-main", 1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        old.getRuleDefinition().setStatus("active");
        old.setAppliedAt(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get("acme", "prod", "corporate-main", 1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private DomainRuleMaterialization materialization(String key, String payload, int version) {
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId("acme")
                .environment("prod")
                .ruleKey("palette:" + key)
                .ruleType(GovernedColorPaletteProjectionService.RULE_TYPE)
                .version(version)
                .status("active")
                .createdByType("human")
                .createdBy("designer@acme.example")
                .build();
        return DomainRuleMaterialization.builder()
                .id(UUID.randomUUID())
                .tenantId("acme")
                .environment("prod")
                .ruleDefinition(definition)
                .materializationKey("palette:" + key + ":v" + version)
                .targetLayer(GovernedColorPaletteProjectionService.TARGET_LAYER)
                .targetArtifactType(GovernedColorPaletteProjectionService.TARGET_ARTIFACT_TYPE)
                .targetArtifactKey(key)
                .status("applied")
                .materializedPayload(payload)
                .appliedAt(Instant.parse("2026-09-03T12:00:00Z"))
                .build();
    }

    private String validPayload() {
        return """
                {
                  "paletteKey":"corporate-main",
                  "displayName":"Acme Corporate",
                  "familyKey":"acme.corporate",
                  "variant":{"key":"light","displayName":"Light","dimensions":{"colorScheme":"light"}},
                  "scope":{"brand":"acme","theme":"light","purposes":["text","surface"]},
                  "entries":[
                    {"tokenId":"brand.primary","displayName":"Primary brand","aliases":["Main brand"],"semanticRole":"primary","cssReference":"var(--acme-primary)","fallback":"#006874","purposes":["text","fill","focus"]},
                    {"tokenId":"surface.default","displayName":"Default surface","aliases":[],"semanticRole":"surface","cssReference":"var(--acme-surface)","fallback":"#ffffff","purposes":["surface"]}
                  ],
                  "contrastEvidence":[
                    {"foregroundTokenId":"brand.primary","backgroundTokenId":"surface.default","ratio":1,"requiredLevel":"AA"}
                  ],
                  "provenance":{"source":"design-system","approvedBy":"brand-council"}
                }
                """;
    }
}
