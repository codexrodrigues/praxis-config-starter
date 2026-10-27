package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringStreamCapacityStore.ReservationStatus;

@Tag("unit")
class InMemoryAgenticAuthoringStreamCapacityStoreTest {

    @Test
    void enforcesGlobalTenantUserAndExecutorQuotasAndReleasesPermitsDeterministically() {
        InMemoryAgenticAuthoringStreamCapacityStore store = new InMemoryAgenticAuthoringStreamCapacityStore();
        UUID stream1 = UUID.randomUUID();
        UUID stream2 = UUID.randomUUID();
        UUID stream3 = UUID.randomUUID();

        assertThat(store.tryReserve(stream1, "tenant-a", "user-1", 2, 2, 1, () -> true))
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(store.tryReserve(stream1, "tenant-a", "user-1", 2, 2, 1, () -> true))
                .isEqualTo(ReservationStatus.ALREADY_RESERVED);
        assertThat(store.activeGlobalCount()).isEqualTo(1);
        assertThat(store.activeTenantCount("tenant-a")).isEqualTo(1);
        assertThat(store.activeUserCount("tenant-a", "user-1")).isEqualTo(1);

        // User quota exceeded for tenant-a:user-1
        assertThat(store.tryReserve(stream2, "tenant-a", "user-1", 2, 2, 1, () -> true))
                .isEqualTo(ReservationStatus.USER_LIMIT_EXCEEDED);

        // Executor saturation check when quotas pass
        assertThat(store.tryReserve(stream2, "tenant-a", "user-2", 2, 2, 1, () -> false))
                .isEqualTo(ReservationStatus.EXECUTOR_SATURATED);

        // Reserve second slot for tenant-a:user-2
        assertThat(store.tryReserve(stream2, "tenant-a", "user-2", 2, 2, 1, () -> true))
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(store.activeGlobalCount()).isEqualTo(2);
        assertThat(store.activeTenantCount("tenant-a")).isEqualTo(2);

        // Tenant quota exceeded for tenant-a even when global limit is raised
        assertThat(store.tryReserve(stream3, "tenant-a", "user-3", 5, 2, 1, () -> true))
                .isEqualTo(ReservationStatus.TENANT_LIMIT_EXCEEDED);

        // Global quota exceeded for tenant-b when global limit is 2
        assertThat(store.tryReserve(stream3, "tenant-b", "user-1", 2, 2, 1, () -> true))
                .isEqualTo(ReservationStatus.GLOBAL_LIMIT_EXCEEDED);

        // Release stream1 and verify counters decrement
        assertThat(store.release(stream1)).isTrue();
        assertThat(store.release(stream1)).isFalse();
        assertThat(store.activeGlobalCount()).isEqualTo(1);
        assertThat(store.activeTenantCount("tenant-a")).isEqualTo(1);
        assertThat(store.activeUserCount("tenant-a", "user-1")).isZero();

        store.clear();
        assertThat(store.activeGlobalCount()).isZero();
    }

    @Test
    void semanticOrientationReconcilerPromotesWorkbenchLayoutFromPreIntentOrientation() {
        AgenticAuthoringSemanticOrientationReconciler reconciler =
                AgenticAuthoringSemanticOrientationReconciler.INSTANCE;
        AgenticAuthoringPreIntentToolPlan orientation = new AgenticAuthoringPreIntentToolPlan(
                "v3",
                "approval workbench",
                List.of(),
                "authoring_or_other",
                "",
                true,
                null,
                "page",
                "praxis-table",
                "approval-workbench");
        AgenticAuthoringLlmIntentResolution resolution = new AgenticAuthoringLlmIntentResolution(
                true,
                "create",
                "page",
                "create_page",
                "/api/operations/missoes",
                null,
                "none",
                "Mesa de aprovação pronta.",
                List.of(),
                List.of(),
                List.of(),
                null,
                new AgenticAuthoringVisualizationDecision(
                        "v1",
                        "create_page",
                        "resource-master-detail",
                        "praxis-table",
                        List.of(),
                        false,
                        true,
                        List.of(),
                        true,
                        false,
                        "llm"),
                false,
                "component_authoring",
                null,
                List.of());

        AgenticAuthoringLlmIntentResolution reconciled =
                reconciler.reconcilePreIntentWorkbenchOrientation(resolution, orientation);

        assertThat(reconciled.visualizationDecision()).isNotNull();
        assertThat(reconciled.visualizationDecision().layoutKind()).isEqualTo("approval-workbench");
        assertThat(reconciled.warnings()).contains("llm-pre-intent-workbench-orientation-reconciled");
    }
}
