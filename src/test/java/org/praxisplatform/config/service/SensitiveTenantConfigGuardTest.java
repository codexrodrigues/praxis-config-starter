package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.autoconfig.UiConfigWriteAuthorizationAutoConfiguration;
import org.praxisplatform.uischema.annotation.DomainClassification;
import org.praxisplatform.uischema.annotation.DomainDataCategory;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.DomainGovernancePreset;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;

@Tag("unit")
class SensitiveTenantConfigGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void blocksTenantUpsertContainingCanonicalOrNestedSensitiveCriteria() {
        SensitiveTenantConfigGuard guard = new SensitiveTenantConfigGuard(true, List.of(), List.of());

        ObjectNode directCpf = MAPPER.createObjectNode();
        directCpf.putObject("criteria").put("cpf", "12345678901");
        assertDenied(guard, "tenant", "upsert", directCpf);

        ObjectNode structuredCriterion = MAPPER.createObjectNode();
        structuredCriterion
                .putArray("defaultCriteria")
                .addObject()
                .put("field", "salario_base")
                .put("value", 5000);
        assertDenied(guard, "tenant", "upsert", structuredCriterion);

        ObjectNode hiddenEmailValue = MAPPER.createObjectNode();
        hiddenEmailValue.putObject("filters").put("quickSearch", "analyst@corp.example");
        assertDenied(guard, "tenant", "upsert", hiddenEmailValue);
    }

    @Test
    void discoversCustomFieldsAnnotatedWithDomainGovernanceAndFilterable() {
        MetadataGovernanceSensitiveCriteriaDiscoverer discoverer =
                new MetadataGovernanceSensitiveCriteriaDiscoverer(
                        List.of(), List.of(SampleGovernedFilterDto.class));
        SensitiveTenantConfigGuard guard =
                new SensitiveTenantConfigGuard(true, List.of(), List.of(discoverer));

        ObjectNode badgePayload = MAPPER.createObjectNode();
        badgePayload.putObject("criteria").put("nationalRegistryPin", "998877");
        assertDenied(guard, "tenant", "upsert", badgePayload);

        ObjectNode relationPayload = MAPPER.createObjectNode();
        relationPayload.putObject("criteria").put("internalCompensationTier", "EXEC-1");
        assertDenied(guard, "tenant", "upsert", relationPayload);

        ObjectNode safePayload = MAPPER.createObjectNode();
        safePayload
                .putObject("criteria")
                .put("departmentCode", "ENG-01")
                .put("employmentStatus", "ACTIVE");
        assertThatCode(() -> guard.assertAllowed(request("tenant", "upsert", safePayload)))
                .doesNotThrowAnyException();
    }

    @Test
    void permitsUserScopeDeletesAndDisabledGuardMode() {
        SensitiveTenantConfigGuard guard = new SensitiveTenantConfigGuard(true, List.of(), List.of());
        ObjectNode sensitive = MAPPER.createObjectNode();
        sensitive.putObject("criteria").put("cpf", "12345678901");

        assertThatCode(() -> guard.assertAllowed(request("user", "upsert", sensitive)))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAllowed(request("tenant", "delete", sensitive)))
                .doesNotThrowAnyException();

        SensitiveTenantConfigGuard disabledGuard =
                new SensitiveTenantConfigGuard(false, List.of(), List.of());
        assertThatCode(() -> disabledGuard.assertAllowed(request("tenant", "upsert", sensitive)))
                .doesNotThrowAnyException();
    }

    @Test
    void autoConfigurationSupportsPackageScanningAndWorksWithoutMetadataStarter() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(
                                        UiConfigWriteAuthorizationAutoConfiguration.class));

        runner.withPropertyValues(
                        "praxis.config.ui-write-guard.dto-base-packages=org.praxisplatform.config.service")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(UiConfigWriteAuthorizer.class);
                            UiConfigWriteAuthorizer authorizer =
                                    context.getBean(UiConfigWriteAuthorizer.class);
                            ObjectNode payload = MAPPER.createObjectNode();
                            payload.putObject("criteria").put("restrictedMedicalNote", "confidential");
                            assertThatThrownBy(
                                            () ->
                                                    authorizer.authorize(
                                                            request("tenant", "upsert", payload)))
                                    .isInstanceOf(UiConfigWriteDeniedException.class)
                                    .extracting(
                                            ex ->
                                                    ((UiConfigWriteDeniedException) ex)
                                                            .getErrorCode())
                                    .isEqualTo(
                                            SensitiveTenantConfigGuard
                                                    .SENSITIVE_CRITERIA_ERROR_CODE);
                        });

        runner.withClassLoader(new FilteredClassLoader(DomainGovernance.class, Filterable.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(SensitiveTenantConfigGuard.class);
                            assertThat(context).hasSingleBean(UiConfigWriteAuthorizer.class);
                        });
    }

    private static void assertDenied(
            SensitiveTenantConfigGuard guard, String scope, String op, ObjectNode payload) {
        assertThatThrownBy(() -> guard.assertAllowed(request(scope, op, payload)))
                .isInstanceOf(UiConfigWriteDeniedException.class)
                .satisfies(
                        ex -> {
                            UiConfigWriteDeniedException denied =
                                    (UiConfigWriteDeniedException) ex;
                            assertThat(denied.getStatusCode())
                                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(denied.getErrorCode())
                                    .isEqualTo(
                                            SensitiveTenantConfigGuard
                                                    .SENSITIVE_CRITERIA_ERROR_CODE);
                        });
    }

    private static UiConfigWriteAuthorizationRequest request(
            String scope, String op, ObjectNode payload) {
        return new UiConfigWriteAuthorizationRequest(
                op,
                scope,
                "tenant-a",
                "user-1",
                "praxis-table",
                "workforce.employees",
                "local",
                "user-1",
                payload,
                null);
    }

    static class SampleGovernedFilterDto {
        @DomainGovernance(preset = DomainGovernancePreset.LGPD_PERSONAL_ID)
        private String nationalRegistryPin;

        @DomainGovernance(
                classification = DomainClassification.CONFIDENTIAL,
                dataCategory = DomainDataCategory.FINANCIAL)
        @Filterable(relation = "contract.internalCompensationTier")
        private String compensationFilter;

        @DomainGovernance(complianceTags = {"LGPD"})
        private String restrictedMedicalNote;

        @DomainGovernance(
                classification = DomainClassification.PUBLIC,
                dataCategory = DomainDataCategory.OPERATIONAL)
        private String departmentCode;
    }
}
