package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;

@Tag("unit")
class DomainFederationRetrievalPolicyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainFederationRetrievalPolicyService service = new DomainFederationRetrievalPolicyService();

    @Test
    void excludesDeniedItemsAndReportsGovernanceSignals() throws Exception {
        List<DomainCatalogItemResponse> items = List.of(
                item("allowed", "{\"confidence\":0.95}"),
                item("masked", "{\"payloadMode\":\"governed-summary\",\"contextVisibility\":\"mask\"}"),
                item("low-confidence", "{\"confidence\":0.41}"),
                item("denied", "{\"aiUsage\":{\"visibility\":\"deny\"}}"));

        var result = service.apply(items);

        assertThat(result.items()).extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly("allowed", "masked", "low-confidence");
        assertThat(result.report().inputItemCount()).isEqualTo(4);
        assertThat(result.report().returnedItemCount()).isEqualTo(3);
        assertThat(result.report().policyProfile()).isEqualTo("explanation");
        assertThat(result.report().minConfidence()).isEqualTo(0.7d);
        assertThat(result.report().includeDenied()).isFalse();
        assertThat(result.report().includeLowConfidence()).isTrue();
        assertThat(result.report().deniedItemCount()).isEqualTo(1);
        assertThat(result.report().governedSummaryItemCount()).isEqualTo(1);
        assertThat(result.report().lowConfidenceItemCount()).isEqualTo(1);
        assertThat(result.report().decisions())
                .anySatisfy(decision -> assertThat(decision).contains("Excluded denied"))
                .anySatisfy(decision -> assertThat(decision).contains("Flagged low-confidence"));
    }

    @Test
    void appliesRuntimePolicyOptions() throws Exception {
        List<DomainCatalogItemResponse> items = List.of(
                item("visible", "{\"confidence\":0.91}"),
                item("borderline", "{\"confidence\":0.72}"),
                item("denied", "{\"aiUsage\":{\"visibility\":\"deny\"},\"confidence\":0.99}"));

        var result = service.apply(items, new DomainFederationRetrievalPolicyOptions("diagnostics", 0.8d, true, false));

        assertThat(result.items()).extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly("visible", "denied");
        assertThat(result.report().policyProfile()).isEqualTo("diagnostics");
        assertThat(result.report().minConfidence()).isEqualTo(0.8d);
        assertThat(result.report().includeDenied()).isTrue();
        assertThat(result.report().includeLowConfidence()).isFalse();
        assertThat(result.report().deniedItemCount()).isEqualTo(1);
        assertThat(result.report().lowConfidenceItemCount()).isEqualTo(1);
        assertThat(result.report().decisions())
                .anySatisfy(decision -> assertThat(decision).contains("Included denied despite aiUsage.visibility=deny"))
                .anySatisfy(decision -> assertThat(decision).contains("Excluded borderline because confidence is below minConfidence"));
    }

    @Test
    void appliesNamedPolicyProfileDefaults() throws Exception {
        List<DomainCatalogItemResponse> items = List.of(
                item("strong", "{\"confidence\":0.91}"),
                item("borderline", "{\"confidence\":0.72}"),
                item("denied", "{\"aiUsage\":{\"visibility\":\"deny\"},\"confidence\":0.99}"));

        var result = service.apply(items, new DomainFederationRetrievalPolicyOptions("authoring", null, null, null));

        assertThat(result.items()).extracting(DomainCatalogItemResponse::itemKey)
                .containsExactly("strong");
        assertThat(result.report().policyProfile()).isEqualTo("authoring");
        assertThat(result.report().minConfidence()).isEqualTo(0.8d);
        assertThat(result.report().includeDenied()).isFalse();
        assertThat(result.report().includeLowConfidence()).isFalse();
    }

    private DomainCatalogItemResponse item(String key, String payload) throws Exception {
        return new DomainCatalogItemResponse(
                UUID.randomUUID(),
                "release",
                "node",
                key,
                "context",
                "entity",
                null,
                null,
                objectMapper.readTree(payload));
    }
}
