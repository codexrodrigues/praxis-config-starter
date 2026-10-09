package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.UiLayoutRelease;
import org.praxisplatform.config.domain.UiLayoutReleaseMember;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class CanonicalUiLayoutReleaseValidatorTest {

    private final CanonicalUiLayoutReleaseValidator validator = new CanonicalUiLayoutReleaseValidator();

    @Test
    void validatesSingleMemberReleaseSuccessfully() {
        UUID releaseId = UUID.randomUUID();
        UiLayoutRelease release = createRelease(releaseId, "praxis-table", "orders-table");
        UiLayoutReleaseMember member = createMember(releaseId, 0, "praxis-table", "orders-table", "key-root");

        assertThatCode(() -> validator.validate(release, List.of(member)))
                .doesNotThrowAnyException();
    }

    @Test
    void validatesMultiMemberReleaseSuccessfully() {
        UUID releaseId = UUID.randomUUID();
        UiLayoutRelease release = createRelease(releaseId, "praxis-table", "orders-table");
        UiLayoutReleaseMember m0 = createMember(releaseId, 0, "praxis-table", "orders-table", "key-table");
        UiLayoutReleaseMember m1 = createMember(releaseId, 1, "praxis-dynamic-form", "order-detail-form", "key-form");
        UiLayoutReleaseMember m2 = createMember(releaseId, 2, "praxis-widget", "order-metrics", "key-metrics");

        assertThatCode(() -> validator.validate(release, List.of(m0, m1, m2)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullOrEmptyReleaseAndMembers() {
        UUID releaseId = UUID.randomUUID();
        UiLayoutRelease release = createRelease(releaseId, "praxis-table", "orders-table");

        assertThatThrownBy(() -> validator.validate(null, List.of()))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE));

        assertThatThrownBy(() -> validator.validate(release, null))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE));

        assertThatThrownBy(() -> validator.validate(release, List.of()))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE));
    }

    @Test
    void rejectsNonSequentialMemberOrder() {
        UUID releaseId = UUID.randomUUID();
        UiLayoutRelease release = createRelease(releaseId, "praxis-table", "orders-table");
        UiLayoutReleaseMember m0 = createMember(releaseId, 1, "praxis-table", "orders-table", "key-0");

        assertThatThrownBy(() -> validator.validate(release, List.of(m0)))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE));
    }

    @Test
    void rejectsDuplicateTargetInMembers() {
        UUID releaseId = UUID.randomUUID();
        UiLayoutRelease release = createRelease(releaseId, "praxis-table", "orders-table");
        UiLayoutReleaseMember m0 = createMember(releaseId, 0, "praxis-table", "orders-table", "key-0");
        UiLayoutReleaseMember m1 = createMember(releaseId, 1, "praxis-table", "orders-table", "key-1");

        assertThatThrownBy(() -> validator.validate(release, List.of(m0, m1)))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE));
    }

    @Test
    void rejectsFirstMemberMismatchingRootTarget() {
        UUID releaseId = UUID.randomUUID();
        UiLayoutRelease release = createRelease(releaseId, "praxis-table", "orders-table");
        UiLayoutReleaseMember m0 = createMember(releaseId, 0, "praxis-other", "other-component", "key-0");

        assertThatThrownBy(() -> validator.validate(release, List.of(m0)))
                .isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UiLayoutLifecycleException.Code.INVALID_RELEASE));
    }

    private UiLayoutRelease createRelease(UUID releaseId, String rootType, String rootId) {
        UiLayoutRelease r = new UiLayoutRelease();
        r.setId(releaseId);
        r.setTenantId("tenant-1");
        r.setEnvironment("prod");
        r.setRootComponentType(rootType);
        r.setRootComponentId(rootId);
        r.setCreatedAt(Instant.now());
        return r;
    }

    private UiLayoutReleaseMember createMember(UUID releaseId, int order, String type, String id, String key) {
        UiLayoutReleaseMember m = new UiLayoutReleaseMember();
        m.setId(UUID.randomUUID());
        m.setReleaseId(releaseId);
        m.setMemberOrder(order);
        m.setComponentType(type);
        m.setComponentId(id);
        m.setContentRevisionId(UUID.randomUUID());
        m.setAssignmentRevisionId(UUID.randomUUID());
        m.setContributionKey(key);
        return m;
    }
}
