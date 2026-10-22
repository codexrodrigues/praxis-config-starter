package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** P0 corpus checks only. No page codec, authorization, database or HTTP implementation is exercised. */
@Tag("unit")
class PagePublicationP0ContractTest {
  private static final Path ROOT = Path.of("docs/ai/contracts/page-publication-p0");
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final UiLayoutPatchDocumentDescriptor PATCH =
      new UiLayoutPatchDocumentDescriptor("fixture:patch", "praxis.ui-layout/v1");

  private JsonNode read(String file) throws Exception {
    return JSON.readTree(ROOT.resolve(file).toFile());
  }

  private static Stream<JsonNode> nodes(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false);
  }

  private void validate(String contract, JsonNode value, boolean valid) throws Exception {
    ObjectNode schema = (ObjectNode) read("contracts.schema.json");
    assertTrue(schema.path("$defs").has(contract), "Unknown contract: " + contract);
    schema.put("$ref", "#/$defs/" + contract);
    var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(schema).validate(value);
    assertEquals(valid, errors.isEmpty(), () -> "Structural errors: " + errors);
  }

  @TestFactory
  Stream<DynamicTest> structuralExamples() throws Exception {
    return nodes(read("corpus.json").path("schemaCases")).map(c -> DynamicTest.dynamicTest(
        c.path("id").asText(), () -> validate(c.path("contract").asText(), c.get("input"), c.path("valid").asBoolean())));
  }

  @TestFactory
  Stream<DynamicTest> nativeExamplesUseExistingGuardsAndCompiler() throws Exception {
    return nodes(read("corpus.json").path("nativeCases")).map(c -> DynamicTest.dynamicTest(
        c.path("id").asText(), () -> {
          if (c.path("valid").asBoolean()) nativeRelation(c);
          else assertThrows(UiLayoutLifecycleException.class, () -> nativeRelation(c));
        }));
  }

  private void nativeRelation(JsonNode c) {
    JsonNode baseline = c.get("baseline"), candidate = c.get("candidate");
    boolean table = c.path("owner").asText().equals("table");
    var target = new UiLayoutTarget(table ? "praxis-table" : "praxis-dynamic-form", "fixture-child");
    var descriptor = new UiLayoutAuthoringDocumentDescriptor(
        table ? "praxis.table.editor" : "praxis.dynamic-form.editor", "fixture:native", "1");
    var patch = UiLayoutAuthoredPatchCompiler.compile((ObjectNode) baseline.get("config"), (ObjectNode) candidate.get("config"));
    if (table) UiLayoutTableRevisionReproduction.require(target, descriptor, baseline, candidate, PATCH, patch);
    else UiLayoutFormRevisionReproduction.require(target, descriptor, baseline, candidate, PATCH, patch);
  }

  @TestFactory
  Stream<DynamicTest> relationalExamplesAreInternallyConsistent() throws Exception {
    return nodes(read("scenarios.json").path("relationalCases")).map(c -> DynamicTest.dynamicTest(
        c.path("id").asText(), () -> {
          validate("pageDocument", c.get("page"), true);
          validate("groupsDocument", c.get("groups"), true);
          Set<String> expected = new LinkedHashSet<>();
          c.path("errors").forEach(e -> expected.add(e.asText()));
          assertEquals(expected, exampleRelations(c.get("page"), c.get("groups")));
        }));
  }

  // Corpus invariant checker, deliberately not exported as a runtime validator or policy engine.
  private Set<String> exampleRelations(JsonNode page, JsonNode groups) {
    Set<String> errors = new LinkedHashSet<>();
    Set<String> widgetIds = unique(page.at("/config/definition/widgets"), "key", "DUPLICATE_WIDGET", errors);
    Set<String> groupIds = unique(groups.at("/config/groups"), "id", "DUPLICATE_GROUP", errors);
    unique(page.at("/config/navigation"), "id", "DUPLICATE_BINDING", errors);
    JsonNode links = page.at("/config/definition/composition/links");
    unique(links, "id", "DUPLICATE_LINK", errors);
    page.at("/config/navigation").forEach(b -> {
      if (!groupIds.contains(b.path("groupId").asText())) errors.add("MISSING_GROUP");
    });
    links.forEach(l -> {
      if (!widgetIds.contains(l.at("/from/ref/widget").asText())
          || !widgetIds.contains(l.at("/to/ref/widget").asText())) errors.add("ORPHAN_LINK");
    });
    return errors;
  }

  private Set<String> unique(JsonNode array, String field, String diagnostic, Set<String> errors) {
    Set<String> ids = new HashSet<>();
    array.forEach(item -> { if (!ids.add(item.path(field).asText())) errors.add(diagnostic); });
    return ids;
  }

  @Test
  void copyExampleHasCompleteFreshMapAndPreservesNonReferenceStrings() throws Exception {
    for (JsonNode example : read("scenarios.json").path("copyExamples")) {
      ObjectNode source = (ObjectNode) example.get("source");
      ObjectNode candidate = (ObjectNode) example.get("candidate");
      validate("pageDocument", source, true);
      validate("pageDocument", candidate, true);
      JsonNode evidence = example.get("evidence");
      validate("copyEvidence", evidence, true);
      var hashes = new CanonicalJsonHashService(JSON);
      assertEquals(evidence.path("sourceHash").asText(), hashes.sha256Exact(source));
      assertEquals(evidence.path("outputHash").asText(), hashes.sha256Exact(candidate));
      assertEquals(example.get("identityMap"), evidence.get("identityMap"));
      Map<String, String> mapping = new java.util.HashMap<>();
      Set<String> destinations = new HashSet<>();
      Set<String> sourceIds = new HashSet<>();
      Set<String> sourceTokens = new HashSet<>();
      source.at("/config/definition/widgets").forEach(w -> {
        sourceIds.add("widget:" + w.path("key").asText());
        sourceTokens.add(w.path("key").asText());
      });
      source.at("/config/definition/composition/links").forEach(l -> {
        sourceIds.add("link:" + l.path("id").asText());
        sourceTokens.add(l.path("id").asText());
      });
      for (JsonNode entry : example.path("identityMap")) {
        String sourceCollection = entry.path("kind").asText().equals("widget") ? "widgets" : "composition/links";
        String identityField = entry.path("kind").asText().equals("widget") ? "key" : "id";
        assertTrue(nodes(source.at("/config/definition/" + sourceCollection))
            .anyMatch(item -> item.path(identityField).equals(entry.get("from"))), "Map kind must match source identity");
        assertNull(mapping.put(entry.path("kind").asText() + ":" + entry.path("from").asText(), entry.path("to").asText()));
        assertTrue(destinations.add(entry.path("to").asText()));
      }
      assertEquals(sourceIds, mapping.keySet());
      assertTrue(java.util.Collections.disjoint(sourceTokens, destinations));
      ObjectNode expected = source.deepCopy();
      expected.at("/config/definition/widgets").forEach(w ->
          ((ObjectNode) w).put("key", mapping.get("widget:" + w.path("key").asText())));
      expected.at("/config/definition/composition/links").forEach(l -> {
        ((ObjectNode) l).put("id", mapping.get("link:" + l.path("id").asText()));
        for (String end : Set.of("from", "to")) {
          ObjectNode endpoint = (ObjectNode) l.path(end).path("ref");
          endpoint.put("widget", mapping.get("widget:" + endpoint.path("widget").asText()));
        }
      });
      ((ObjectNode) expected.at("/config/presentation")).put("title", example.path("title").asText());
      ((ArrayNode) expected.at("/config/navigation")).removeAll();
      assertEquals(expected, candidate, "Only declared identities/title/navigation change; configs stay intact");
    }
  }

  @Test
  void existingBudgetsApplyToWholeDocumentIncludingAtomicArrays() throws Exception {
    ObjectNode page = (ObjectNode) read("corpus.json").path("schemaCases").get(0).get("input").deepCopy();
    UiLayoutJsonBounds.requireDocument(page, UiLayoutLifecycleException.Code.INVALID_REQUEST, () -> {});
    ObjectNode config = (ObjectNode) page.at("/config/definition/widgets/0/definition/inputs/config");
    config.put("oversizedFixture", "á".repeat(UiLayoutJsonBounds.MAX_DOCUMENT_BYTES / 2));
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutJsonBounds.requireDocument(page, UiLayoutLifecycleException.Code.INVALID_REQUEST, () -> {}));
    config.remove("oversizedFixture");
    ObjectNode cursor = config;
    for (int n = 0; n < UiLayoutJsonBounds.MAX_DEPTH; n++) cursor = cursor.putObject("nested");
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutJsonBounds.requireDocument(page, UiLayoutLifecycleException.Code.INVALID_REQUEST, () -> {}));
  }

  @Test
  void p0DoesNotPretendPageAndGroupsAlreadyHaveNativeProjection() throws Exception {
    for (String contract : Set.of("pageDocument", "groupsDocument")) {
      JsonNode example = nodes(read("corpus.json").path("schemaCases"))
          .filter(c -> c.path("contract").asText().equals(contract) && c.path("valid").asBoolean())
          .findFirst().orElseThrow().get("input");
      var target = new UiLayoutTarget(contract.equals("pageDocument") ? "praxis-dynamic-page" : "praxis-app-shell", "fixture-root");
      var descriptor = new UiLayoutAuthoringDocumentDescriptor(example.path("kind").asText(), "fixture:proposed", "1");
      assertThrows(UiLayoutLifecycleException.class, () ->
          UiLayoutAuthoringProjection.project(target, descriptor, example, example, PATCH, () -> {}));
    }
    // This assertion is deliberately removed/replaced by positive codec tests in P1.
  }

  @Test
  void existingParserRejectsAmbiguousDuplicateMembersAndTrailingJson() {
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutRevisionJsonInput.readDocument("{\"config\":{},\"config\":{\"changed\":true}}", () -> {}));
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutRevisionJsonInput.readDocument("{} {}", () -> {}));
  }

  @Test
  void corpusIdsAreUniqueAndIntegrationGatesAreNotClaimedAsExecuted() throws Exception {
    Set<String> ids = new HashSet<>();
    for (String file : Set.of("corpus.json", "scenarios.json")) {
      JsonNode document = read(file);
      for (String section : Set.of("schemaCases", "nativeCases", "relationalCases", "copyExamples", "integrationGates")) {
        for (JsonNode c : document.path(section)) {
          assertFalse(c.path("id").asText().isBlank());
          assertTrue(ids.add(c.path("id").asText()), "Duplicate case identity");
          if (section.equals("integrationGates")) {
            assertEquals("not-executed", c.path("status").asText());
            assertFalse(c.path("failureInvariant").asText().isBlank());
          }
        }
      }
    }
  }
}
