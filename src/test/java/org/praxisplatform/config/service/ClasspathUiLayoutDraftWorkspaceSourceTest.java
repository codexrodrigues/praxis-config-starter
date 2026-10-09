package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class ClasspathUiLayoutDraftWorkspaceSourceTest {

    private final UiLayoutLifecycleAdmission operations = mock(UiLayoutLifecycleAdmission.class);
    private final UiLayoutBaselineMetadataAdmission contentAdmission = mock(UiLayoutBaselineMetadataAdmission.class);
    private final UiLayoutLifecycleStructureValidator structure = mock(UiLayoutLifecycleStructureValidator.class);

    private ClasspathUiLayoutDraftWorkspaceSource source;
    private final UiLayoutTarget sampleTarget = new UiLayoutTarget("praxis-table", "orders-grid");
    private final UiLayoutCompositionRegistration composition =
            new UiLayoutCompositionRegistration(sampleTarget, List.of(sampleTarget));
    private final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation(
            "test-actor", "tenant-1", "org-1", "prod", "v1", composition);

    @BeforeEach
    void setUp() {
        source = new ClasspathUiLayoutDraftWorkspaceSource(operations, contentAdmission, structure);
        source.register(new ClasspathUiLayoutDraftWorkspaceSource.BaselinePublication(
                sampleTarget,
                "test-layouts/sample.table-authoring.v1.json",
                "classpath:test-layouts/sample.table-authoring.v1.json",
                "praxis.table.editor",
                "enterprise.orders.table-presentation",
                null,
                "praxis-platform",
                "test-policy"
        ));
    }

    @Test
    void capturesRegisteredPublicationSuccessfully() {
        var attempt = UiLayoutValidationAttempt.start(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofMinutes(1));
        var context = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);

        var seed = source.capture(invocation, context);

        assertThat(seed).isNotNull();
        assertThat(seed.targets()).hasSize(1);

        var targetSeed = seed.targets().getFirst();
        assertThat(targetSeed.target()).isEqualTo(sampleTarget);
        assertThat(targetSeed.authoring().documentType()).isEqualTo("praxis.table.editor");
        assertThat(targetSeed.document().path("kind").asText()).isEqualTo("praxis.table.editor");
        assertThat(targetSeed.document().path("version").asInt()).isEqualTo(1);
        assertThat(targetSeed.metadata().source()).isInstanceOf(UiLayoutBaselineMetadataSeed.NativeDocumentSource.class);

        var nativeSource = (UiLayoutBaselineMetadataSeed.NativeDocumentSource) targetSeed.metadata().source();
        assertThat(nativeSource.documentRevision()).matches("[a-f0-9]{64}");

        verify(operations, org.mockito.Mockito.times(2)).require(UiLayoutLifecycleOperation.CREATE_DRAFT, invocation);
        verify(contentAdmission).require(eq(invocation), eq(sampleTarget), any(), any(), any(), any());
        verify(structure).validateAuthoringBaseline(eq(invocation), eq(sampleTarget), any(), any(), eq(context));
    }

    @Test
    void throwsSourceUnavailableForUnregisteredTarget() {
        UiLayoutTarget unregistered = new UiLayoutTarget("praxis-table", "unknown-grid");
        UiLayoutCompositionRegistration unregComp =
                new UiLayoutCompositionRegistration(unregistered, List.of(unregistered));
        UiLayoutLifecycleInvocation unregInv = new UiLayoutLifecycleInvocation(
                "actor", "tenant-1", "org-1", "prod", "v1", unregComp);

        var attempt = UiLayoutValidationAttempt.start(unregInv, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofMinutes(1));
        var context = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.AUTHORING);

        assertThatThrownBy(() -> source.capture(unregInv, context))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
    }

    @Test
    void throwsDeniedForMismatchedPurposeOrOperation() {
        var attempt = UiLayoutValidationAttempt.start(invocation, UiLayoutLifecycleOperation.CREATE_DRAFT, Duration.ofMinutes(1));
        var context = new UiLayoutValidationContext(attempt, UiLayoutValidationPurpose.FROZEN_RELEASE);

        assertThatThrownBy(() -> source.capture(invocation, context))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.DENIED));
    }
}
