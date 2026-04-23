package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainFederationContext;
import org.praxisplatform.config.dto.DomainFederationContextRelationship;
import org.praxisplatform.config.dto.DomainFederationContract;
import org.praxisplatform.config.dto.DomainFederationResolution;
import org.praxisplatform.config.dto.DomainFederationSource;
import org.praxisplatform.config.dto.DomainFederationValidationIssue;
import org.praxisplatform.config.dto.DomainFederationValidationRequest;

@Tag("unit")
class DomainFederationContractValidatorTest {

    private final DomainFederationContractValidator validator = new DomainFederationContractValidator();

    @Test
    void validatesReadOnlyFederationContract() {
        var report = validator.validate(validRequest());

        assertThat(report.valid()).isTrue();
        assertThat(report.errorCount()).isZero();
        assertThat(report.warningCount()).isZero();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void rejectsUnknownReferencesAndMissingContractForCrossContextDependency() {
        var request = new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(source("operations-service", "Operations", "Operacoes")),
                List.of(context("operations", "operations-service", "Operacoes")),
                List.of(new DomainFederationContextRelationship(
                        "operations.missoes.uses.assets.veiculos",
                        "operations",
                        "assets",
                        "uses",
                        null,
                        "source_to_target",
                        "target_owned",
                        0.91,
                        "active",
                        null)),
                List.of(),
                List.of(new DomainFederationResolution(
                        "operations.vehicle.maps_to.assets.vehicle",
                        "operations.vehicle",
                        "assets.vehicle",
                        "operations",
                        "assets",
                        "maps_to",
                        0.88,
                        "approved",
                        "Architecture",
                        null)));

        var report = validator.validate(request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainFederationValidationIssue::code)
                .contains(
                        "relationship.targetContextKey.unknown",
                        "relationship.contractKey.required",
                        "resolution.targetContextKey.unknown");
    }

    @Test
    void reportsReviewWarningsWithoutBlockingValidFederationEnvelope() {
        var request = new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(
                        source("operations-service", "Operations", "Operacoes"),
                        source("assets-service", "Assets", "Frota")),
                List.of(
                        context("operations", "operations-service", "Operacoes"),
                        context("assets", "assets-service", "Frota")),
                List.of(new DomainFederationContextRelationship(
                        "operations.missoes.uses.assets.veiculos",
                        "operations",
                        "assets",
                        "uses",
                        "assets.vehicle-allocation-changed.v1",
                        "bidirectional",
                        "shared",
                        0.62,
                        "active",
                        null)),
                List.of(new DomainFederationContract(
                        "assets.vehicle-allocation-changed.v1",
                        "event_schema",
                        "assets-service",
                        "assets",
                        "operations",
                        "assets.veiculos",
                        "VehicleAllocationChanged",
                        "asyncapi://assets-service/events/VehicleAllocationChanged/v1",
                        "experimental",
                        "internal",
                        "active",
                        null)),
                List.of(new DomainFederationResolution(
                        "operations.vehicle.same_as.assets.vehicle",
                        "operations.vehicle",
                        "assets.vehicle",
                        "operations",
                        "assets",
                        "same_as",
                        0.78,
                        "review_required",
                        "Architecture",
                        null)));

        var report = validator.validate(request);

        assertThat(report.valid()).isTrue();
        assertThat(report.errorCount()).isZero();
        assertThat(report.warningCount()).isEqualTo(3);
        assertThat(report.issues())
                .extracting(DomainFederationValidationIssue::code)
                .containsExactlyInAnyOrder(
                        "contract.experimental",
                        "relationship.lowConfidence",
                        "resolution.lowConfidence");
    }

    @Test
    void rejectsUntrustedActiveSourceBecauseLlmContextMustNotUseIt() {
        var report = validator.validate(new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(new DomainFederationSource(
                        "security-service",
                        "microservice",
                        "security-service",
                        "Security",
                        "tenant-a",
                        "dev",
                        "Security",
                        "Security Platform",
                        "untrusted",
                        "active",
                        "domain-catalog:security:v1",
                        null)),
                List.of(context("security", "security-service", "Security")),
                List.of(),
                List.of(),
                List.of()));

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainFederationValidationIssue::code)
                .contains("source.untrustedActive");
    }

    @Test
    void rejectsSourceAndContextOutsideRequestScope() {
        var report = validator.validate(new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(new DomainFederationSource(
                        "assets-service",
                        "microservice",
                        "assets-service",
                        "Assets",
                        "tenant-b",
                        "prod",
                        "Frota",
                        "Assets Platform",
                        "authoritative",
                        "active",
                        "domain-catalog:assets:v1",
                        null)),
                List.of(new DomainFederationContext(
                        "assets",
                        "assets-service",
                        "bounded_context",
                        "Assets",
                        "Vehicle and fleet concepts.",
                        "Frota",
                        "Assets Platform",
                        "tenant-b",
                        "prod",
                        "active",
                        "domain-catalog:assets:v1",
                        null)),
                List.of(),
                List.of(),
                List.of()));

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainFederationValidationIssue::code)
                .contains(
                        "source.tenantId.mismatch",
                        "source.environment.mismatch",
                        "context.tenantId.mismatch",
                        "context.environment.mismatch");
    }

    private DomainFederationValidationRequest validRequest() {
        return new DomainFederationValidationRequest(
                DomainFederationContractValidator.SCHEMA_VERSION,
                "tenant-a",
                "dev",
                List.of(
                        source("operations-service", "Operations", "Operacoes"),
                        source("assets-service", "Assets", "Frota")),
                List.of(
                        context("operations", "operations-service", "Operacoes"),
                        context("assets", "assets-service", "Frota")),
                List.of(new DomainFederationContextRelationship(
                        "operations.missoes.uses.assets.veiculos",
                        "operations",
                        "assets",
                        "uses",
                        "assets.vehicle-allocation-changed.v1",
                        "bidirectional",
                        "shared",
                        0.92,
                        "active",
                        null)),
                List.of(new DomainFederationContract(
                        "assets.vehicle-allocation-changed.v1",
                        "event_schema",
                        "assets-service",
                        "assets",
                        "operations",
                        "assets.veiculos",
                        "VehicleAllocationChanged",
                        "asyncapi://assets-service/events/VehicleAllocationChanged/v1",
                        "stable",
                        "internal",
                        "active",
                        null)),
                List.of(new DomainFederationResolution(
                        "operations.vehicle.same_as.assets.vehicle",
                        "operations.vehicle",
                        "assets.vehicle",
                        "operations",
                        "assets",
                        "same_as",
                        0.93,
                        "approved",
                        "Architecture",
                        null)));
    }

    private DomainFederationSource source(String sourceKey, String serviceName, String owner) {
        return new DomainFederationSource(
                sourceKey,
                "microservice",
                sourceKey,
                serviceName,
                "tenant-a",
                "dev",
                owner,
                serviceName + " Platform",
                "authoritative",
                "active",
                "domain-catalog:" + sourceKey + ":v1",
                null);
    }

    private DomainFederationContext context(String contextKey, String sourceKey, String owner) {
        return new DomainFederationContext(
                contextKey,
                sourceKey,
                "bounded_context",
                contextKey,
                contextKey + " context",
                owner,
                owner + " Platform",
                "tenant-a",
                "dev",
                "active",
                "domain-catalog:" + contextKey + ":v1",
                null);
    }
}
