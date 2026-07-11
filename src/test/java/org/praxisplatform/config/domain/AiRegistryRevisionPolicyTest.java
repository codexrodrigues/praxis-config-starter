package org.praxisplatform.config.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AiRegistryRevisionPolicyTest {

  @Test
  void insertInitializesVersionEtagStatusAndTimestamps() {
    AiRegistry registry =
        AiRegistry.builder()
            .registryType("template")
            .registryKey("praxis-table")
            .componentType("template")
            .scope(Scope.SYSTEM)
            .scopeKey("GLOBAL")
            .payload("{}")
            .build();

    registry.onInsert();

    assertThat(registry.getVersion()).isEqualTo(1L);
    assertThat(registry.getEtag()).isNotNull();
    assertThat(registry.getStatus()).isEqualTo("active");
    assertThat(registry.getCreatedAt()).isNotNull();
    assertThat(registry.getUpdatedAt()).isEqualTo(registry.getCreatedAt());
  }

  @Test
  void materialChangeIncrementsVersionAndRotatesEtagAtomically() {
    UUID originalEtag = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    AiRegistry registry =
        AiRegistry.builder()
            .payload("{\"a\":1}")
            .embedding(List.of(0.1f))
            .tags("{\"kind\":\"old\"}")
            .source("classpath")
            .sourceRef("old.json")
            .status("active")
            .version(3L)
            .etag(originalEtag)
            .build();

    boolean changed =
        registry.applyMaterialState(
            "{\"a\":2}", List.of(0.2f), "{\"kind\":\"new\"}", "external", "new.json", "active");

    assertThat(changed).isTrue();
    assertThat(registry.getVersion()).isEqualTo(4L);
    assertThat(registry.getEtag()).isNotEqualTo(originalEtag);
    assertThat(registry.getPayload()).isEqualTo("{\"a\":2}");
    assertThat(registry.getEmbedding()).containsExactly(0.2f);
    assertThat(registry.getTags()).isEqualTo("{\"kind\":\"new\"}");
    assertThat(registry.getSource()).isEqualTo("external");
    assertThat(registry.getSourceRef()).isEqualTo("new.json");
  }

  @Test
  void identicalMaterialStateDoesNotChangeVersionOrEtag() {
    UUID originalEtag = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
    AiRegistry registry =
        AiRegistry.builder()
            .payload("{\"a\":1}")
            .embedding(List.of(0.1f))
            .tags("{\"kind\":\"same\"}")
            .source("classpath")
            .sourceRef("registry.json")
            .status("active")
            .version(7L)
            .etag(originalEtag)
            .build();

    boolean changed =
        registry.applyMaterialState(
            "{\"a\":1}", List.of(0.1f), "{\"kind\":\"same\"}", "classpath", "registry.json", null);

    assertThat(changed).isFalse();
    assertThat(registry.getVersion()).isEqualTo(7L);
    assertThat(registry.getEtag()).isEqualTo(originalEtag);
  }
}
