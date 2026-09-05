package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

@Tag("unit")
class UiUserConfigRepositoryContractTest {

  @Test
  void shouldBindConditionalUpdatePayloadsAsPostgresJsonb() throws Exception {
    Method updateIfCurrent = UiUserConfigRepository.class.getMethod(
        "updateIfCurrent",
        UUID.class,
        String.class,
        String.class,
        String.class,
        long.class,
        UUID.class,
        UUID.class,
        Instant.class,
        String.class);

    Query query = updateIfCurrent.getAnnotation(Query.class);

    assertThat(query).isNotNull();
    assertThat(query.nativeQuery()).isTrue();
    assertThat(query.value())
        .contains("payload = CAST(:payload AS jsonb)")
        .contains("authoring_source = CAST(:authoringSource AS jsonb)")
        .contains("tags = CAST(:tags AS jsonb)")
        .contains("AND etag = :expectedEtag");
  }
}
