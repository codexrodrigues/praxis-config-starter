package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PageCreationCodecTest {
  private final PageCreationCodec codec = new PageCreationCodec();

  @Test
  void rawClosedSchemaPrecedesJacksonBinding() {
    String valid =
        "{\"creationKey\":\"11111111-1111-4111-8111-111111111111\",\"source\":{\"kind\":\"registered-native\",\"sourceId\":\"fixture\",\"revision\":\"r1\"},\"title\":\"Copy\"}";
    assertEquals("Copy", codec.command(valid).title());
    for (String raw :
        java.util.List.of(
            valid.replace("\"title\":\"Copy\"", "\"title\":\"Copy\",\"tenant\":\"forged\""),
            valid.replace("\"title\":\"Copy\"", "\"title\":\"Copy\",\"title\":\"Again\""),
            valid + " {}",
            valid.replace("\"sourceId\":\"fixture\"", "\"sourceId\":\"fixture\",\"document\":{}"),
            valid.replace("\"title\":\"Copy\"", "\"title\":null"),
            valid.replace("11111111-1111-4111-8111-111111111111", "1-1-1-1-1"),
            valid.replace("\"revision\":\"r1\"", "\"revision\":1")))
      assertThrows(UiLayoutLifecycleException.class, () -> codec.command(raw));
  }

  @Test
  void receiptSerializationConformsToP0IncludingSourceDiscriminatorAndQuotedEtag()
      throws Exception {
    var schema =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            UiLayoutPageRevisionReproductionTest.JSON.readTree(
                Path.of("docs/ai/contracts/page-publication-p0/contracts.schema.json").toFile());
    schema.put("$ref", "#/$defs/creationReceipt");
    var validator =
        com.networknt.schema.JsonSchemaFactory.getInstance(
                com.networknt.schema.SpecVersion.VersionFlag.V202012)
            .getSchema(schema);
    var page = java.util.UUID.randomUUID();
    for (var source :
        java.util.List.of(
            new org.praxisplatform.config.dto.PageSourceReference.Native("fixture", "r1"),
            new org.praxisplatform.config.dto.PageSourceReference.Published(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID()))) {
      var receipt =
          new org.praxisplatform.config.dto.PageCreationReceipt(
              java.util.UUID.randomUUID(),
              page,
              new org.praxisplatform.config.dto.UiLayoutTarget(
                  "praxis-dynamic-page", page.toString()),
              "/pages/" + page,
              java.util.UUID.randomUUID(),
              "\"" + java.util.UUID.randomUUID() + "\"",
              source);
      assertTrue(validator.validate(codec.tree(receipt)).isEmpty());
    }
  }

  @Test
  void executesEveryNormativeCreationCase() throws Exception {
    var corpus =
        UiLayoutPageRevisionReproductionTest.JSON.readTree(
            Path.of("docs/ai/contracts/page-publication-p0/corpus.json").toFile());
    for (var row : corpus.path("schemaCases"))
      if ("createCommand".equals(row.path("contract").asText())) {
        if (row.path("valid").asBoolean())
          assertNotNull(codec.command(row.get("input").toString()), row.path("id").asText());
        else
          assertThrows(
              UiLayoutLifecycleException.class,
              () -> codec.command(row.get("input").toString()),
              row.path("id").asText());
      }
  }
}
